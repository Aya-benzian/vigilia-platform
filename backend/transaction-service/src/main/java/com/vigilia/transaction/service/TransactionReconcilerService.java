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
import com.vigilia.transaction.dto.TransactionResponse;import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Step 2: Reconciler for transactions left in PENDING (e.g. when Risk was unreachable
 * or the service crashed between TX-1 and TX-2).
 *
 * <ul>
 *   <li>Selects stale PENDING transactions using {@code FOR UPDATE SKIP LOCKED} inside one
 *       short {@code @Transactional} selection method so multiple replicas never duplicate work.</li>
 *   <li>Rebuilds {@link RiskEvaluationRequest} from stored data (using {@code transfers.sender_balance_before}
 *       for transfers) and calls Risk OUTSIDE any DB transaction.</li>
 *   <li>Applies the decision in a new transaction via {@code findByIdForUpdate} + status guard +
 *       {@link TransactionServiceImpl#applyDecision}. Duplicate or late decisions are no-ops.</li>
 * </ul>
 */
@Service
public class TransactionReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(TransactionReconcilerService.class);

    private final TransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final TransferRepository transferRepository;
    private final WalletService walletService;
    private final RiskEvaluationClient riskEvaluationClient;
    private final TransactionServiceImpl transactionService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate selectTx;
    private final TransactionTemplate applyTx;

    private final boolean enabled;
    private final long minAgeSeconds;
    private final int batchSize;

    private final Counter retriedCounter;
    private final Counter appliedCounter;
    private final Counter stillFailingCounter;

    @Autowired
    public TransactionReconcilerService(
            TransactionRepository transactionRepository,
            PaymentRepository paymentRepository,
            TransferRepository transferRepository,
            WalletService walletService,
            RiskEvaluationClient riskEvaluationClient,
            TransactionServiceImpl transactionService,
            IdempotencyService idempotencyService,
            PlatformTransactionManager txManager,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Value("${transaction.reconciler.enabled:true}") boolean enabled,
            @Value("${transaction.reconciler.min-age-seconds:60}") long minAgeSeconds,
            @Value("${transaction.reconciler.batch-size:50}") int batchSize) {
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.transferRepository = transferRepository;
        this.walletService = walletService;
        this.riskEvaluationClient = riskEvaluationClient;
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
        this.enabled = enabled;
        this.minAgeSeconds = minAgeSeconds;
        this.batchSize = batchSize;

        TransactionTemplate selectTemplate = new TransactionTemplate(txManager);
        selectTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.selectTx = selectTemplate;

        TransactionTemplate applyTemplate = new TransactionTemplate(txManager);
        applyTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        applyTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.applyTx = applyTemplate;

        if (meterRegistry != null) {
            this.retriedCounter = meterRegistry.counter("vigilia.transaction.reconciler.retried");
            this.appliedCounter = meterRegistry.counter("vigilia.transaction.reconciler.applied");
            this.stillFailingCounter = meterRegistry.counter("vigilia.transaction.reconciler.still_failing");
        } else {
            this.retriedCounter = null;
            this.appliedCounter = null;
            this.stillFailingCounter = null;
        }
    }

    @Scheduled(fixedDelayString = "${transaction.reconciler.delay-ms:30000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcilePendingTransactions() {
        if (!enabled) {
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(Math.max(0L, minAgeSeconds));
        List<UUID> pendingIds = selectPendingIdsForReconciliation(cutoff);
        if (pendingIds.isEmpty()) {
            return;
        }

        int retried = 0;
        int applied = 0;
        int stillFailing = 0;

        for (UUID txnId : pendingIds) {
            retried++;
            if (retriedCounter != null) retriedCounter.increment();

            Optional<RiskEvaluationRequest> reqOpt = buildRiskRequestFromStoredData(txnId);
            if (reqOpt.isEmpty()) {
                continue;
            }

            // Call Risk OUTSIDE any DB transaction
            RiskEvaluationResponse decision;
            try {
                decision = riskEvaluationClient.evaluate(reqOpt.get());
            } catch (RiskUnavailableException ex) {
                stillFailing++;
                if (stillFailingCounter != null) stillFailingCounter.increment();
                log.warn("[RECONCILER] Risk still unavailable for txnId={}: {}", txnId, ex.getMessage());
                continue;
            } catch (Exception ex) {
                stillFailing++;
                if (stillFailingCounter != null) stillFailingCounter.increment();
                log.error("[RECONCILER] Unexpected error evaluating txnId={}: {}", txnId, ex.getMessage(), ex);
                continue;
            }

            boolean didApply = applyDecisionInNewTx(txnId, decision);
            if (didApply) {
                applied++;
                if (appliedCounter != null) appliedCounter.increment();
                // Q4 fix: update the idempotency cache so retries with same key
                // return the final settled state instead of a stale PENDING snapshot.
                updateIdempotencyCacheAfterReconciliation(txnId);
            }
        }

        log.info("[RECONCILER] Run completed: retried={}, applied={}, stillFailing={}",
                retried, applied, stillFailing);
    }

    /**
     * Distributed-safe selection inside one short transaction using FOR UPDATE SKIP LOCKED.
     */
    List<UUID> selectPendingIdsForReconciliation(Instant cutoff) {
        return selectTx.execute(status ->
                transactionRepository.findPendingOlderThanForUpdate(cutoff, batchSize)
                        .stream()
                        .map(TransactionEntity::getId)
                        .toList()
        );
    }

    private Optional<RiskEvaluationRequest> buildRiskRequestFromStoredData(UUID txnId) {
        Optional<TransactionEntity> txnOpt = transactionRepository.findById(txnId);
        if (txnOpt.isEmpty() || !"PENDING".equalsIgnoreCase(txnOpt.get().getStatus())) {
            return Optional.empty();
        }
        TransactionEntity txn = txnOpt.get();
        TransactionType type = TransactionType.valueOf(txn.getTransactionType());

        RiskEvaluationRequest req = new RiskEvaluationRequest(
                txn.getId(),
                txn.getCustomerId(),
                type,
                txn.getAmount(),
                txn.getCurrency(),
                txn.getCreatedAt() != null ? txn.getCreatedAt() : Instant.now()
        );

        if (type == TransactionType.PURCHASE) {
            paymentRepository.findById(txnId).ifPresent(payment -> {
                if (payment.getMerchantId() != null) {
                    req.setMerchantId(payment.getMerchantId().toString());
                }
                req.setMerchantCategory(payment.getMerchantCategory());
                if (payment.getCustomerLat() != null) {
                    req.setMerchantLatitude(payment.getCustomerLat().doubleValue());
                }
                if (payment.getCustomerLon() != null) {
                    req.setMerchantLongitude(payment.getCustomerLon().doubleValue());
                }
            });
        } else if (type == TransactionType.TRANSFER) {
            transferRepository.findByTransactionId(txnId).ifPresent(transfer -> {
                if (transfer.getRecipientCustomerId() != null) {
                    req.setRecipientId(transfer.getRecipientCustomerId().toString());
                }
                if (transfer.getSenderBalanceBefore() != null) {
                    req.setSenderBalance(transfer.getSenderBalanceBefore().doubleValue());
                }
            });
        }

        return Optional.of(req);
    }

    private boolean applyDecisionInNewTx(UUID txnId, RiskEvaluationResponse decision) {
        Boolean result = applyTx.execute(status -> {
            Optional<TransactionEntity> lockedOpt = transactionRepository.findByIdForUpdate(txnId);
            if (lockedOpt.isEmpty()) {
                return false;
            }
            TransactionEntity locked = lockedOpt.get();
            if (!"PENDING".equalsIgnoreCase(locked.getStatus())) {
                log.debug("[RECONCILER] txnId={} already in status {}; skipping", txnId, locked.getStatus());
                return false;
            }

            UUID senderWalletId;
            UUID recipientWalletId = null;
            if (TransactionType.TRANSFER.name().equalsIgnoreCase(locked.getTransactionType())) {
                TransferEntity transfer = transferRepository.findByTransactionId(txnId)
                        .orElseThrow(() -> new IllegalStateException("Missing transfer detail for txnId=" + txnId));
                senderWalletId = transfer.getSenderWalletId();
                recipientWalletId = transfer.getRecipientWalletId();
            } else {
                WalletEntity wallet = walletService.getWalletEntityByCustomerId(locked.getCustomerId());
                senderWalletId = wallet.getId();
            }

            transactionService.applyDecision(locked, senderWalletId, recipientWalletId, locked.getAmount(), decision);
            transactionRepository.save(locked);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    /**
     * Q4 fix: After the reconciler applies a decision, update the idempotency_keys row
     * so that a same-key retry returns the final settled state (APPROVED/REJECTED/PENDING_REVIEW)
     * instead of the stale PENDING snapshot that was stored when TX-1 committed.
     */
    private void updateIdempotencyCacheAfterReconciliation(UUID txnId) {
        try {
            transactionRepository.findById(txnId).ifPresent(txn -> {
                String idemKey = txn.getIdempotencyKey();
                if (idemKey == null || idemKey.isBlank()) return;

                String scope = idempotencyService.buildScope(txn.getCustomerId());
                // Build a minimal response reflecting the settled state
                com.vigilia.transaction.dto.RiskStatus statusEnum;
                try { statusEnum = com.vigilia.transaction.dto.RiskStatus.valueOf(txn.getStatus()); }
                catch (Exception e) { statusEnum = com.vigilia.transaction.dto.RiskStatus.PENDING_REVIEW; }

                com.vigilia.transaction.dto.TransactionType typeEnum;
                try { typeEnum = com.vigilia.transaction.dto.TransactionType.valueOf(txn.getTransactionType()); }
                catch (Exception e) { typeEnum = com.vigilia.transaction.dto.TransactionType.PURCHASE; }

                com.vigilia.transaction.dto.TransactionResponse response = new com.vigilia.transaction.dto.TransactionResponse(
                        txn.getId(), txn.getCustomerId(), typeEnum, txn.getAmount(),
                        txn.getCurrency(), statusEnum, null,
                        "Decision: " + txn.getStatus(), txn.getCreatedAt());

                int httpStatus = switch (statusEnum) {
                    case APPROVED, REJECTED, PENDING_REVIEW -> 201;
                    default -> 202;
                };

                // We need a stable hash — reuse empty string as placeholder (the original hash is in DB already)
                idempotencyService.recordResponse(scope, idemKey, "", httpStatus, response, null);
                log.debug("[RECONCILER] Idempotency cache updated for key={}, txnId={}, status={}", idemKey, txnId, statusEnum);
            });
        } catch (Exception e) {
            // Non-fatal: log and continue — the transaction was settled successfully
            log.warn("[RECONCILER] Failed to update idempotency cache for txnId={}: {}", txnId, e.getMessage());
        }
    }
}

