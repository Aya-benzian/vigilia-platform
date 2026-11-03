package com.vigilia.transaction.service;

import com.vigilia.transaction.client.RiskEvaluationClient;
import com.vigilia.transaction.dto.IdempotencyCheckResult;
import com.vigilia.transaction.dto.PaginationMeta;
import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.entity.MerchantEntity;
import com.vigilia.transaction.entity.PaymentEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.entity.TransactionStatusHistoryEntity;
import com.vigilia.transaction.entity.TransferEntity;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.exception.IdempotencyConflictException;
import com.vigilia.transaction.exception.InsufficientFundsException;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.exception.ResourceNotFoundException;
import com.vigilia.transaction.repository.MerchantRepository;
import com.vigilia.transaction.repository.PaymentRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.repository.TransactionStatusHistoryRepository;
import com.vigilia.transaction.repository.TransferRepository;
import com.vigilia.transaction.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransactionStatusHistoryRepository statusHistoryRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private RiskEvaluationClient riskEvaluationClient;

    @Mock
    private TransactionOutboxService transactionOutboxService;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    private UUID customerId;
    private UUID walletId;
    private WalletEntity customerWallet;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        customerWallet = new WalletEntity(customerId, "FR7630006000011234567890189", "EUR", new BigDecimal("500.00"));
        customerWallet.setId(walletId);
    }

    // =========================================================================
    // 1. PURCHASE Flow Tests
    // =========================================================================

    @Test
    @DisplayName("PURCHASE - Succès avec décision de risque APPROVED : hold, création, règlement et archivage")
    void createPurchase_Success_Approved() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("49.99"),
                "EUR"
        );
        request.setMerchantName("Fnac Paris");
        request.setCategory("ELECTRONICS");
        request.setMerchantLatitude(48.8566);
        request.setMerchantLongitude(2.3522);

        String idempotencyKey = "idem-key-123";
        String clientIp = "192.168.1.10";

        when(idempotencyService.buildScope(customerId)).thenReturn("customer:" + customerId);
        when(idempotencyService.calculateRequestHash(request)).thenReturn("hash123");
        when(idempotencyService.checkOrThrow("customer:" + customerId, idempotencyKey, "hash123"))
                .thenReturn(IdempotencyCheckResult.newKey("hash123"));

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);

        MerchantEntity merchant = new MerchantEntity("MCH_FNAC", "Fnac Paris", "ELECTRONICS");
        merchant.setId(UUID.randomUUID());
        when(merchantRepository.findByNameIgnoreCase("Fnac Paris")).thenReturn(Optional.of(merchant));

        UUID txnId = UUID.randomUUID();
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> {
            TransactionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(txnId);
            }
            return entity;
        });

        RiskEvaluationResponse approvedRisk = new RiskEvaluationResponse(
                UUID.randomUUID(), txnId, RiskStatus.APPROVED, 0.05, "Low risk score", Instant.now()
        );
        when(riskEvaluationClient.evaluate(any(RiskEvaluationRequest.class))).thenReturn(approvedRisk);

        // Execution
        TransactionResponse response = transactionService.createTransaction(request, idempotencyKey, clientIp);

        // Verifications
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(txnId);
        assertThat(response.getStatus()).isEqualTo(RiskStatus.APPROVED);
        assertThat(response.getAmount()).isEqualByComparingTo("49.99");
        assertThat(response.getCurrency()).isEqualTo("EUR");

        // 1. Funds held first
        verify(walletService).holdFunds(walletId, null, new BigDecimal("49.99"));

        // 2. Transaction saved (initial PENDING, then finalized APPROVED)
        verify(transactionRepository, times(2)).save(any(TransactionEntity.class));

        // 3. Payment details saved
        verify(paymentRepository).save(any(PaymentEntity.class));

        // 4. Status history saved (null -> PENDING then PENDING -> APPROVED)
        verify(statusHistoryRepository, times(2)).save(any(TransactionStatusHistoryEntity.class));

        // 5. Funds settled
        verify(walletService).settleHold(walletId, txnId, new BigDecimal("49.99"));
        verify(walletService, never()).releaseFunds(any(), any(), any());

        // 6. Idempotency cached
        verify(idempotencyService).recordResponse(
                eq("customer:" + customerId),
                eq(idempotencyKey),
                eq("hash123"),
                eq(201),
                any(TransactionResponse.class),
                nullable(java.time.Duration.class)
        );
    }

    @Test
    @DisplayName("PURCHASE - Rejet par le risque : libération des fonds et statut REJECTED")
    void createPurchase_RiskRejected() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("100.00"),
                "EUR"
        );
        request.setMerchantName("Casino Online");

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);

        MerchantEntity merchant = new MerchantEntity("MCH_CASINO", "Casino Online", "RETAIL");
        merchant.setId(UUID.randomUUID());
        when(merchantRepository.findByNameIgnoreCase("Casino Online")).thenReturn(Optional.of(merchant));

        UUID txnId = UUID.randomUUID();
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> {
            TransactionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(txnId);
            }
            return entity;
        });

        RiskEvaluationResponse rejectedRisk = new RiskEvaluationResponse(
                UUID.randomUUID(), txnId, RiskStatus.REJECTED, 0.95, "Suspected fraud", Instant.now()
        );
        when(riskEvaluationClient.evaluate(any(RiskEvaluationRequest.class))).thenReturn(rejectedRisk);

        TransactionResponse response = transactionService.createTransaction(request, null, "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.REJECTED);
        verify(walletService).holdFunds(walletId, null, new BigDecimal("100.00"));
        verify(walletService).releaseFunds(walletId, txnId, new BigDecimal("100.00"));
        verify(walletService, never()).settleHold(any(), any(), any());
    }

    @Test
    @DisplayName("PURCHASE - Réponse Risque PENDING_REVIEW : les fonds restent réservés")
    void createPurchase_RiskPendingReview() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("250.00"),
                "EUR"
        );
        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);

        MerchantEntity merchant = new MerchantEntity("MCH_DEFAULT", "Default Merchant", "RETAIL");
        merchant.setId(UUID.randomUUID());
        when(merchantRepository.findByNameIgnoreCase("Default Merchant")).thenReturn(Optional.of(merchant));

        UUID txnId = UUID.randomUUID();
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> {
            TransactionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(txnId);
            }
            return entity;
        });

        RiskEvaluationResponse reviewRisk = new RiskEvaluationResponse(
                UUID.randomUUID(), txnId, RiskStatus.PENDING_REVIEW, 0.65, "Unusual location", Instant.now()
        );
        when(riskEvaluationClient.evaluate(any(RiskEvaluationRequest.class))).thenReturn(reviewRisk);

        TransactionResponse response = transactionService.createTransaction(request, null, "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.PENDING_REVIEW);
        verify(walletService).holdFunds(walletId, null, new BigDecimal("250.00"));
        // Funds remain held: neither settle nor release
        verify(walletService, never()).settleHold(any(), any(), any());
        verify(walletService, never()).releaseFunds(any(), any(), any());
    }

    @Test
    @DisplayName("PURCHASE - Solde insuffisant : exception propagée et aucune transaction créée")
    void createPurchase_InsufficientFunds() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("99999.00"),
                "EUR"
        );
        request.setMerchantName("Store");
        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);

        MerchantEntity merchant = new MerchantEntity("MCH_STORE", "Store", "RETAIL");
        merchant.setId(UUID.randomUUID());
        when(merchantRepository.findByNameIgnoreCase("Store")).thenReturn(Optional.of(merchant));

        when(walletService.holdFunds(walletId, null, new BigDecimal("99999.00")))
                .thenThrow(new InsufficientFundsException("Solde disponible insuffisant"));

        assertThatThrownBy(() -> transactionService.createTransaction(request, null, "127.0.0.1"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Solde disponible insuffisant");

        verify(transactionRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(riskEvaluationClient);
    }

    @Test
    @DisplayName("PURCHASE - Devise incompatible avec le portefeuille : rejet immédiat")
    void createPurchase_CurrencyMismatch() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("10.00"),
                "USD" // Wallet is in EUR
        );
        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);

        assertThatThrownBy(() -> transactionService.createTransaction(request, null, "127.0.0.1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Incompatibilité de devise");

        verify(walletService, never()).holdFunds(any(), any(), any());
    }

    // =========================================================================
    // 2. Idempotency Replay & Conflict Tests
    // =========================================================================

    @Test
    @DisplayName("Idempotence - Rejeu idempotent : retourne la réponse en cache sans aucun effet financier")
    void createTransaction_IdempotentReplay() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("50.00"),
                "EUR"
        );
        String key = "idem-replay-key";

        when(idempotencyService.buildScope(customerId)).thenReturn("customer:" + customerId);
        when(idempotencyService.calculateRequestHash(request)).thenReturn("hash-abc");

        String cachedJson = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"APPROVED\",\"amount\":50.00,\"currency\":\"EUR\"}";
        when(idempotencyService.checkOrThrow("customer:" + customerId, key, "hash-abc"))
                .thenReturn(IdempotencyCheckResult.cached(201, cachedJson, "hash-abc"));

        TransactionResponse cachedResponse = new TransactionResponse();
        cachedResponse.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        cachedResponse.setStatus(RiskStatus.APPROVED);
        cachedResponse.setAmount(new BigDecimal("50.00"));
        cachedResponse.setCurrency("EUR");

        when(idempotencyService.parseResponseBody(cachedJson, TransactionResponse.class))
                .thenReturn(Optional.of(cachedResponse));

        TransactionResponse result = transactionService.createTransaction(request, key, "127.0.0.1");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(result.getStatus()).isEqualTo(RiskStatus.APPROVED);

        // Verify zero financial operations
        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(riskEvaluationClient);
    }

    @Test
    @DisplayName("Idempotence - Conflit de clé : IdempotencyConflictException propagée immédiatement")
    void createTransaction_IdempotentConflict() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("50.00"),
                "EUR"
        );
        String key = "idem-conflict-key";

        when(idempotencyService.buildScope(customerId)).thenReturn("customer:" + customerId);
        when(idempotencyService.calculateRequestHash(request)).thenReturn("hash-abc");

        when(idempotencyService.checkOrThrow("customer:" + customerId, key, "hash-abc"))
                .thenThrow(new IdempotencyConflictException("Cette clé d'idempotence a déjà été utilisée"));

        assertThatThrownBy(() -> transactionService.createTransaction(request, key, "127.0.0.1"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Cette clé d'idempotence");

        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionRepository);
    }

    // =========================================================================
    // 3. TRANSFER Flow Tests
    // =========================================================================

    @Test
    @DisplayName("TRANSFER - Succès avec décision APPROVED : verrouillage déterministe, hold expéditeur, règlement et crédit destinataire")
    void createTransfer_Success_Approved() {
        UUID recipientCustomerId = UUID.randomUUID();
        UUID recipientWalletId = UUID.randomUUID();
        String recipientIban = "FR7630006000019999999999999";

        WalletEntity recipientWallet = new WalletEntity(recipientCustomerId, recipientIban, "EUR", new BigDecimal("100.00"));
        recipientWallet.setId(recipientWalletId);

        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.TRANSFER,
                new BigDecimal("75.00"),
                "EUR"
        );
        request.setRecipientIban(recipientIban);
        request.setTransferReference("Remboursement resto");

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(walletRepository.findByAccountNumber(recipientIban)).thenReturn(Optional.of(recipientWallet));

        UUID txnId = UUID.randomUUID();
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> {
            TransactionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(txnId);
            }
            return entity;
        });

        RiskEvaluationResponse approvedRisk = new RiskEvaluationResponse(
                UUID.randomUUID(), txnId, RiskStatus.APPROVED, 0.02, "Trusted transfer", Instant.now()
        );
        when(riskEvaluationClient.evaluate(any(RiskEvaluationRequest.class))).thenReturn(approvedRisk);

        TransactionResponse response = transactionService.createTransaction(request, null, "127.0.0.1");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(RiskStatus.APPROVED);

        // Verify deterministic locks acquired on both wallets
        UUID smallerUuid = walletId.compareTo(recipientWalletId) < 0 ? walletId : recipientWalletId;
        UUID largerUuid = walletId.compareTo(recipientWalletId) < 0 ? recipientWalletId : walletId;

        InOrder lockOrder = inOrder(walletRepository);
        lockOrder.verify(walletRepository).findByIdForUpdate(smallerUuid);
        lockOrder.verify(walletRepository).findByIdForUpdate(largerUuid);

        // Sender funds held
        verify(walletService).holdFunds(walletId, null, new BigDecimal("75.00"));

        // Transfer details persisted
        verify(transferRepository).save(any(TransferEntity.class));

        // Sender hold settled AND recipient credited
        verify(walletService).settleHold(walletId, txnId, new BigDecimal("75.00"));
        verify(walletService).creditFunds(recipientWalletId, txnId, new BigDecimal("75.00"));

        // No funds released
        verify(walletService, never()).releaseFunds(any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER - Rejet par le risque : restitution des fonds à l'expéditeur et aucun crédit destinataire")
    void createTransfer_RiskRejected() {
        UUID recipientCustomerId = UUID.randomUUID();
        UUID recipientWalletId = UUID.randomUUID();
        String recipientIban = "FR7630006000019999999999999";

        WalletEntity recipientWallet = new WalletEntity(recipientCustomerId, recipientIban, "EUR", new BigDecimal("100.00"));
        recipientWallet.setId(recipientWalletId);

        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.TRANSFER,
                new BigDecimal("150.00"),
                "EUR"
        );
        request.setRecipientIban(recipientIban);

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(walletRepository.findByAccountNumber(recipientIban)).thenReturn(Optional.of(recipientWallet));

        UUID txnId = UUID.randomUUID();
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> {
            TransactionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(txnId);
            }
            return entity;
        });

        RiskEvaluationResponse rejectedRisk = new RiskEvaluationResponse(
                UUID.randomUUID(), txnId, RiskStatus.REJECTED, 0.98, "High risk IBAN", Instant.now()
        );
        when(riskEvaluationClient.evaluate(any(RiskEvaluationRequest.class))).thenReturn(rejectedRisk);

        TransactionResponse response = transactionService.createTransaction(request, null, "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.REJECTED);

        // Sender hold released
        verify(walletService).releaseFunds(walletId, txnId, new BigDecimal("150.00"));
        verify(walletService, never()).settleHold(any(), any(), any());

        // CRITICAL: Recipient must NEVER be credited on rejection
        verify(walletService, never()).creditFunds(any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER - Virement vers son propre compte interdit")
    void createTransfer_SelfTransfer_Forbidden() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.TRANSFER,
                new BigDecimal("100.00"),
                "EUR"
        );
        request.setRecipientIban(customerWallet.getAccountNumber()); // same IBAN

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(walletRepository.findByAccountNumber(customerWallet.getAccountNumber())).thenReturn(Optional.of(customerWallet));

        assertThatThrownBy(() -> transactionService.createTransaction(request, null, "127.0.0.1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Impossible d'effectuer un virement vers son propre compte");

        verify(walletService, never()).holdFunds(any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER - IBAN destinataire introuvable : ResourceNotFoundException")
    void createTransfer_RecipientNotFound() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.TRANSFER,
                new BigDecimal("100.00"),
                "EUR"
        );
        request.setRecipientIban("FR7600000000000000000000000");

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(walletRepository.findByAccountNumber("FR7600000000000000000000000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.createTransaction(request, null, "127.0.0.1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Portefeuille destinataire introuvable");

        verify(walletService, never()).holdFunds(any(), any(), any());
    }

    @Test
    @DisplayName("TRANSFER - Devise destinataire incompatible : InvalidOperationException")
    void createTransfer_RecipientCurrencyMismatch() {
        UUID recipientWalletId = UUID.randomUUID();
        WalletEntity recipientWallet = new WalletEntity(UUID.randomUUID(), "FR7630006000019999999999999", "USD", new BigDecimal("100.00"));
        recipientWallet.setId(recipientWalletId);

        TransactionCreateRequest request = new TransactionCreateRequest(
                customerId,
                TransactionType.TRANSFER,
                new BigDecimal("50.00"),
                "EUR"
        );
        request.setRecipientIban(recipientWallet.getAccountNumber());

        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(walletRepository.findByAccountNumber(recipientWallet.getAccountNumber())).thenReturn(Optional.of(recipientWallet));

        assertThatThrownBy(() -> transactionService.createTransaction(request, null, "127.0.0.1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Incompatibilité de devise");
    }

    // =========================================================================
    // 4. Analyst Decision Resolution Tests
    // =========================================================================

    @Test
    @DisplayName("Analyst Resolution - PURCHASE PENDING_REVIEW -> APPROVED : règlement des fonds")
    void resolveAnalystDecision_Purchase_Approved() {
        UUID txnId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("80.00"), "EUR", "PENDING_REVIEW");
        txn.setId(txnId);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));
        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.resolveAnalystDecision(txnId, RiskStatus.APPROVED, "Vérification manuelle OK", "analyst-42");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.APPROVED);
        verify(walletService).settleHold(walletId, txnId, new BigDecimal("80.00"));
        verify(walletService, never()).releaseFunds(any(), any(), any());
        verify(statusHistoryRepository).save(any(TransactionStatusHistoryEntity.class));
    }

    @Test
    @DisplayName("Analyst Resolution - PURCHASE PENDING_REVIEW -> REJECTED : restitution des fonds")
    void resolveAnalystDecision_Purchase_Rejected() {
        UUID txnId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("80.00"), "EUR", "PENDING_REVIEW");
        txn.setId(txnId);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));
        when(walletService.getWalletEntityByCustomerId(customerId)).thenReturn(customerWallet);
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.resolveAnalystDecision(txnId, RiskStatus.REJECTED, "Fraude confirmée", "analyst-42");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.REJECTED);
        verify(walletService).releaseFunds(walletId, txnId, new BigDecimal("80.00"));
        verify(walletService, never()).settleHold(any(), any(), any());
    }

    @Test
    @DisplayName("Analyst Resolution - TRANSFER PENDING_REVIEW -> APPROVED : règlement expéditeur et crédit destinataire")
    void resolveAnalystDecision_Transfer_Approved() {
        UUID txnId = UUID.randomUUID();
        UUID recipientWalletId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("TRANSFER", customerId, new BigDecimal("120.00"), "EUR", "PENDING_REVIEW");
        txn.setId(txnId);

        TransferEntity transfer = new TransferEntity(txnId, walletId, recipientWalletId, UUID.randomUUID(), "Ref123");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));
        when(transferRepository.findByTransactionId(txnId)).thenReturn(Optional.of(transfer));
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.resolveAnalystDecision(txnId, RiskStatus.APPROVED, "OK analyste", "analyst-99");

        assertThat(response.getStatus()).isEqualTo(RiskStatus.APPROVED);
        verify(walletService).settleHold(walletId, txnId, new BigDecimal("120.00"));
        verify(walletService).creditFunds(recipientWalletId, txnId, new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("Analyst Resolution - Transition interdite depuis un état déjà APPROVED : InvalidOperationException")
    void resolveAnalystDecision_ForbiddenTransition_FromApproved() {
        UUID txnId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("50.00"), "EUR", "APPROVED");
        txn.setId(txnId);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        assertThatThrownBy(() -> transactionService.resolveAnalystDecision(txnId, RiskStatus.REJECTED, "Trop tard", "analyst-1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Seules les transactions en état PENDING_REVIEW peuvent être résolues");

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Analyst Resolution - Décision PENDING_REVIEW interdite : InvalidOperationException")
    void resolveAnalystDecision_ForbiddenDecision_PendingReview() {
        UUID txnId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionService.resolveAnalystDecision(txnId, RiskStatus.PENDING_REVIEW, "Pas de changement", "analyst-1"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("La décision de l'analyste doit être APPROVED ou REJECTED");
    }

    // =========================================================================
    // 5. Query and Pagination Tests
    // =========================================================================

    @Test
    @DisplayName("Query - getTransactionById retourne la transaction ou lève ResourceNotFoundException")
    void getTransactionById() {
        UUID txnId = UUID.randomUUID();
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("35.00"), "EUR", "APPROVED");
        txn.setId(txnId);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        TransactionResponse res = transactionService.getTransactionById(txnId);
        assertThat(res.getId()).isEqualTo(txnId);

        UUID unknownId = UUID.randomUUID();
        when(transactionRepository.findById(unknownId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> transactionService.getTransactionById(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Query - listTransactions avec pagination")
    @SuppressWarnings("unchecked")
    void listTransactions_Success() {
        TransactionEntity txn = new TransactionEntity("PURCHASE", customerId, new BigDecimal("35.00"), "EUR", "APPROVED");
        txn.setId(UUID.randomUUID());

        when(transactionRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(txn), PageRequest.of(0, 10), 1));

        TransactionPageResponse pageRes = transactionService.listTransactions(customerId, RiskStatus.APPROVED, TransactionType.PURCHASE, PageRequest.of(0, 10));

        assertThat(pageRes.getContent()).hasSize(1);
        assertThat(pageRes.getMeta().getTotalElements()).isEqualTo(1);
    }
}
