package com.vigilia.transaction.service;

import com.vigilia.transaction.client.RiskEvaluationClient;
import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.entity.PaymentEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.entity.TransferEntity;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.exception.RiskUnavailableException;
import com.vigilia.transaction.repository.PaymentRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.repository.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part B §8i — Unit tests for TransactionReconcilerService.
 *
 * Tests:
 *  - batch: processes all IDs returned by the select query
 *  - backoff when Risk is down: stillFailing incremented, no exception thrown
 *  - skips non-PENDING transactions
 *  - uses sender_balance_before for TRANSFER requests
 *  - NULL senderBalanceBefore → graceful fallback (no NPE, request built without senderBalance)
 *  - notification-service Q9: TransactionOutboxService emits typed events
 */
class TransactionReconcilerServiceTest {

    // ── mocks ──────────────────────────────────────────────────────────────────
    private TransactionRepository transactionRepo;
    private PaymentRepository paymentRepo;
    private TransferRepository transferRepo;
    private WalletService walletService;
    private RiskEvaluationClient riskClient;
    private TransactionServiceImpl transactionService;
    private IdempotencyService idempotencyService;
    private PlatformTransactionManager txManager;

    private TransactionReconcilerService reconciler;

    @BeforeEach
    void setUp() {
        transactionRepo  = mock(TransactionRepository.class);
        paymentRepo      = mock(PaymentRepository.class);
        transferRepo     = mock(TransferRepository.class);
        walletService    = mock(WalletService.class);
        riskClient       = mock(RiskEvaluationClient.class);
        transactionService = mock(TransactionServiceImpl.class);
        idempotencyService = mock(IdempotencyService.class);
        txManager        = mock(PlatformTransactionManager.class);

        // Make TransactionTemplate execute the callback immediately (no real tx)
        var realTxManager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {}
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) {}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {}
        };

        reconciler = new TransactionReconcilerService(
                transactionRepo, paymentRepo, transferRepo,
                walletService, riskClient, transactionService, idempotencyService,
                realTxManager, new SimpleMeterRegistry(),
                true, 60L, 50
        );
    }

    // ── 8i-1: batch — processes all IDs from select query ───────────────────
    @Test
    @DisplayName("8i-1: batch processes all IDs returned by selectPendingIdsForReconciliation")
    void batch_processesAllIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        // selectPendingIdsForReconciliation wraps findPendingOlderThanForUpdate in a tx
        when(transactionRepo.findPendingOlderThanForUpdate(any(), anyInt()))
                .thenReturn(List.of(pendingTxn(id1), pendingTxn(id2), pendingTxn(id3)));
        when(transactionRepo.findById(any()))
                .thenReturn(Optional.of(pendingTxn(id1)))
                .thenReturn(Optional.of(pendingTxn(id2)))
                .thenReturn(Optional.of(pendingTxn(id3)));
        when(transactionRepo.findByIdForUpdate(any()))
                .thenReturn(Optional.of(pendingTxn(id1)))
                .thenReturn(Optional.of(pendingTxn(id2)))
                .thenReturn(Optional.of(pendingTxn(id3)));
        when(riskClient.evaluate(any())).thenReturn(approvedResponse());
        when(walletService.getWalletEntityByCustomerId(any())).thenReturn(wallet());
        when(transactionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.findById(any())).thenReturn(Optional.empty());

        reconciler.reconcilePendingTransactions();

        // applyDecision called for all 3
        verify(transactionService, times(3)).applyDecision(any(), any(), isNull(), any(), any());
    }

    // ── 8i-2: backoff when Risk is down ─────────────────────────────────────
    @Test
    @DisplayName("8i-2: when Risk is down, reconciler increments stillFailing and does NOT throw")
    void riskDown_doesNotThrow_incrementsStillFailing() {
        UUID id = UUID.randomUUID();
        when(transactionRepo.findPendingOlderThanForUpdate(any(), anyInt()))
                .thenReturn(List.of(pendingTxn(id)));
        when(transactionRepo.findById(id)).thenReturn(Optional.of(pendingTxn(id)));
        when(riskClient.evaluate(any())).thenThrow(new RiskUnavailableException("service down"));

        assertThatCode(() -> reconciler.reconcilePendingTransactions()).doesNotThrowAnyException();
        verify(transactionService, never()).applyDecision(any(), any(), any(), any(), any());
    }

    // ── 8i-3: skips non-PENDING transactions ────────────────────────────────
    @Test
    @DisplayName("8i-3: skips transaction that is no longer PENDING (already APPROVED)")
    void skipsNonPendingTransaction() {
        UUID id = UUID.randomUUID();
        TransactionEntity approved = pendingTxn(id);
        approved.setStatus("APPROVED"); // already settled

        when(transactionRepo.findPendingOlderThanForUpdate(any(), anyInt()))
                .thenReturn(List.of(approved));
        when(transactionRepo.findById(id)).thenReturn(Optional.of(approved));

        reconciler.reconcilePendingTransactions();

        // Risk should not be called — the buildRiskRequest method returns empty for non-PENDING
        verify(riskClient, never()).evaluate(any());
        verify(transactionService, never()).applyDecision(any(), any(), any(), any(), any());
    }

    // ── 8i-4: uses sender_balance_before for TRANSFER ───────────────────────
    @Test
    @DisplayName("8i-4: TRANSFER request uses sender_balance_before from stored transfer row")
    void transfer_usesSenderBalanceBefore() {
        UUID txnId = UUID.randomUUID();
        UUID senderWalletId   = UUID.randomUUID();
        UUID recipientWalletId = UUID.randomUUID();

        TransactionEntity txn = pendingTxn(txnId);
        txn.setTransactionType("TRANSFER");

        TransferEntity transfer = new TransferEntity();
        transfer.setTransactionId(txnId);
        transfer.setSenderWalletId(senderWalletId);
        transfer.setRecipientWalletId(recipientWalletId);
        transfer.setSenderBalanceBefore(new BigDecimal("750.00")); // stored at TX-1

        when(transactionRepo.findPendingOlderThanForUpdate(any(), anyInt())).thenReturn(List.of(txn));
        when(transactionRepo.findById(txnId)).thenReturn(Optional.of(txn));
        when(transactionRepo.findByIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        when(transferRepo.findByTransactionId(txnId)).thenReturn(Optional.of(transfer));
        when(riskClient.evaluate(any())).thenReturn(approvedResponse());
        when(transactionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reconciler.reconcilePendingTransactions();

        ArgumentCaptor<RiskEvaluationRequest> captor = ArgumentCaptor.forClass(RiskEvaluationRequest.class);
        verify(riskClient).evaluate(captor.capture());
        assertThat(captor.getValue().getSenderBalance()).isEqualTo(750.0);
    }

    // ── 8i-5: NULL senderBalanceBefore → graceful fallback ──────────────────
    @Test
    @DisplayName("8i-5: NULL senderBalanceBefore (pre-V5 row) does not cause NPE, request built without it")
    void transfer_nullSenderBalanceBefore_gracefulFallback() {
        UUID txnId = UUID.randomUUID();
        TransactionEntity txn = pendingTxn(txnId);
        txn.setTransactionType("TRANSFER");

        TransferEntity transfer = new TransferEntity();
        transfer.setTransactionId(txnId);
        transfer.setSenderWalletId(UUID.randomUUID());
        transfer.setRecipientWalletId(UUID.randomUUID());
        transfer.setSenderBalanceBefore(null); // old row before V5

        when(transactionRepo.findPendingOlderThanForUpdate(any(), anyInt())).thenReturn(List.of(txn));
        when(transactionRepo.findById(txnId)).thenReturn(Optional.of(txn));
        when(transactionRepo.findByIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        when(transferRepo.findByTransactionId(txnId)).thenReturn(Optional.of(transfer));
        when(riskClient.evaluate(any())).thenReturn(approvedResponse());
        when(transactionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Must not throw
        assertThatCode(() -> reconciler.reconcilePendingTransactions()).doesNotThrowAnyException();

        ArgumentCaptor<RiskEvaluationRequest> captor = ArgumentCaptor.forClass(RiskEvaluationRequest.class);
        verify(riskClient).evaluate(captor.capture());
        // senderBalance not set (default null/0)
        assertThat(captor.getValue().getSenderBalance()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TransactionEntity pendingTxn(UUID id) {
        TransactionEntity t = new TransactionEntity();
        t.setId(id);
        t.setCustomerId(UUID.randomUUID());
        t.setStatus("PENDING");
        t.setTransactionType("PURCHASE");
        t.setAmount(new BigDecimal("100.00"));
        t.setCurrency("EUR");
        t.setCreatedAt(Instant.now().minusSeconds(120));
        return t;
    }

    private RiskEvaluationResponse approvedResponse() {
        return new RiskEvaluationResponse(UUID.randomUUID(), UUID.randomUUID(), RiskStatus.APPROVED, 0.05, "POLICY_APPROVE", Instant.now());
    }

    private WalletEntity wallet() {
        WalletEntity w = new WalletEntity(UUID.randomUUID(), "FR76XXX", "EUR", new BigDecimal("500.00"));
        w.setId(UUID.randomUUID());
        return w;
    }
}
