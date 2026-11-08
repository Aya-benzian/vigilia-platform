package com.vigilia.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.RiskEvaluationResponse;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.entity.OutboxEventEntity;
import com.vigilia.risk.entity.ReviewCaseEntity;
import com.vigilia.risk.entity.RiskEvaluationEntity;
import com.vigilia.risk.exception.ResourceNotFoundException;
import com.vigilia.risk.repository.DecisionPolicyRepository;
import com.vigilia.risk.repository.OutboxEventRepository;
import com.vigilia.risk.repository.ReviewCaseRepository;
import com.vigilia.risk.repository.RiskEvaluationRepository;
import com.vigilia.risk.service.MlPredictionClient.MlPredictionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core risk evaluation engine.
 *
 * Decision algorithm (in order of precedence):
 *  1. Hard-limit check: amount > max_amount_hard_limit → REJECTED / HARD_LIMIT
 *  2. Velocity check: tx count in last 1h >= velocity_hard_cap_1h → REJECTED / VELOCITY_EXCEEDED
 *  3. ML call → on failure → PENDING_REVIEW / FALLBACK_ML_FAILURE (never APPROVED)
 *  4. High uncertainty → PENDING_REVIEW / UNCERTAINTY
 *  5. probability >= reject_threshold → REJECTED / POLICY_REJECT
 *  6. probability <= approve_threshold → APPROVED / POLICY_APPROVE
 *  7. Ambiguous zone (between thresholds) → PENDING_REVIEW / AMBIGUOUS_ZONE
 *
 * Each evaluation is persisted atomically together with the outbox event.
 * If a PENDING_REVIEW decision is made, a ReviewCase is also created.
 */
@Service
public class RiskEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationService.class);

    /**
     * Feature specification version corresponding to the feature schema
     * defined in ml/training/artifacts/feature_spec.json (schema_version: "1.0").
     * Note: this is strictly the feature schema version, not an HTTP API contract version.
     */
    public static final String FEATURE_SPEC_VERSION = "1.0";

    // SLA: 4 hours for review cases (configurable)
    @Value("${risk.review.sla-hours:4}")
    private int reviewSlaDurationHours;

    private final RiskEvaluationRepository evaluationRepository;
    private final DecisionPolicyRepository policyRepository;
    private final ReviewCaseRepository reviewCaseRepository;
    private final OutboxEventRepository outboxRepository;
    private final CustomerActivityService customerActivityService;
    private final MlPredictionClient mlClient;
    private final ObjectMapper objectMapper;

    public RiskEvaluationService(RiskEvaluationRepository evaluationRepository,
                                 DecisionPolicyRepository policyRepository,
                                 ReviewCaseRepository reviewCaseRepository,
                                 OutboxEventRepository outboxRepository,
                                 CustomerActivityService customerActivityService,
                                 MlPredictionClient mlClient,
                                 ObjectMapper objectMapper) {
        this.evaluationRepository = evaluationRepository;
        this.policyRepository = policyRepository;
        this.reviewCaseRepository = reviewCaseRepository;
        this.outboxRepository = outboxRepository;
        this.customerActivityService = customerActivityService;
        this.mlClient = mlClient;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Evaluates a transaction for fraud risk.
     * Idempotent: returns the existing evaluation if one already exists for the transaction.
     *
     * @throws IllegalArgumentException if the request is structurally invalid
     */
    @Transactional
    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        UUID transactionId = request.getTransactionId();

        // Idempotency: return cached result if we already evaluated this transaction
        if (evaluationRepository.existsByTransactionId(transactionId)) {
            log.info("[RISK] Duplicate evaluation request for transactionId={}; returning cached result", transactionId);
            RiskEvaluationEntity existing = evaluationRepository.findByTransactionId(transactionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Risk evaluation not found for transactionId: " + transactionId));
            return toResponse(existing);
        }

        String txType = request.getTransactionType() != null ? request.getTransactionType().name() : "PURCHASE";
        log.info("[RISK] Starting evaluation transactionId={}, type={}, amount={}",
                transactionId, txType, request.getAmount());

        // Load active policy (use hardcoded defaults if none found)
        DecisionPolicyEntity policy = policyRepository
                .findByTransactionTypeAndIsActiveTrue(txType)
                .orElse(null);

        BigDecimal rejectThreshold   = policy != null ? policy.getRejectThreshold()   : new BigDecimal("0.70");
        BigDecimal approveThreshold  = policy != null ? policy.getApproveThreshold()  : new BigDecimal("0.30");
        BigDecimal uncertaintyThresh = policy != null ? policy.getUncertaintyThreshold() : new BigDecimal("0.50");
        BigDecimal maxAmount         = policy != null ? policy.getMaxAmountHardLimit() : new BigDecimal("5000.00");
        int velocityCap              = policy != null ? policy.getVelocityHardCap1h() : 15;

        // 1. Hard-limit check
        if (request.getAmount() != null && request.getAmount().compareTo(maxAmount) > 0) {
            log.warn("[RISK] Hard limit exceeded: amount={} > limit={} for transactionId={}",
                    request.getAmount(), maxAmount, transactionId);
            return persist(request, policy, "HARD_LIMIT_EXCEEDED", null, null,
                    RiskStatus.REJECTED, "HARD_LIMIT", null, null);
        }

        // 2. Velocity check
        if (request.getCustomerId() != null) {
            long txCount1h = customerActivityService.countTransactionsInLastHour(request.getCustomerId());
            if (txCount1h >= velocityCap) {
                log.warn("[RISK] Velocity exceeded: count={} >= cap={} for customerId={}",
                        txCount1h, velocityCap, request.getCustomerId());
                return persist(request, policy, "VELOCITY_CAP_EXCEEDED", null, null,
                        RiskStatus.REJECTED, "VELOCITY_EXCEEDED", null, null);
            }
        }

        // 3. ML prediction (PURCHASE or TRANSFER)
        MlPredictionResponse ml = mlClient.predict(request);
        if (ml == null) {
            log.warn("[RISK] ML failure → PENDING_REVIEW for transactionId={}", transactionId);
            return persistAndOpenReview(request, policy, "ML_FAILURE", null, null,
                    "FALLBACK_ML_FAILURE", "HIGH");
        }

        BigDecimal probability  = toBigDecimal(ml.getFraudProbability());
        BigDecimal uncertainty  = toBigDecimal(ml.getUncertainty());
        String mlStatus = "SUCCESS";

        // 4. High uncertainty → review
        if (uncertainty != null && uncertainty.compareTo(uncertaintyThresh) >= 0) {
            log.info("[RISK] High uncertainty={} for transactionId={}", uncertainty, transactionId);
            return persistAndOpenReview(request, policy, mlStatus, ml, probability,
                    "UNCERTAINTY", "MEDIUM");
        }

        // 5. Reject
        if (probability != null && probability.compareTo(rejectThreshold) >= 0) {
            log.info("[RISK] REJECTED probability={} >= threshold={} for transactionId={}",
                    probability, rejectThreshold, transactionId);
            return persist(request, policy, mlStatus, ml, probability,
                    RiskStatus.REJECTED, "POLICY_REJECT", null, null);
        }

        // 6. Approve
        if (probability != null && probability.compareTo(approveThreshold) <= 0) {
            log.info("[RISK] APPROVED probability={} <= threshold={} for transactionId={}",
                    probability, approveThreshold, transactionId);
            return persist(request, policy, mlStatus, ml, probability,
                    RiskStatus.APPROVED, "POLICY_APPROVE", null, null);
        }

        // 7. Ambiguous zone
        log.info("[RISK] Ambiguous zone probability={} for transactionId={}", probability, transactionId);
        return persistAndOpenReview(request, policy, mlStatus, ml, probability,
                "AMBIGUOUS_ZONE", "MEDIUM");
    }

    /**
     * Retrieves an existing evaluation by transaction ID.
     */
    @Transactional(readOnly = true)
    public RiskEvaluationResponse findByTransactionId(UUID transactionId) {
        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No risk evaluation found for transactionId: " + transactionId));
        return toResponse(entity);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private RiskEvaluationResponse persist(RiskEvaluationRequest req,
                                           DecisionPolicyEntity policy,
                                           String mlStatus,
                                           MlPredictionResponse ml,
                                           BigDecimal probability,
                                           RiskStatus decision,
                                           String decisionReason,
                                           ReviewCaseEntity reviewCase,
                                           String priority) {
        RiskEvaluationEntity entity = buildEvaluationEntity(req, policy, mlStatus, ml, probability, decision, decisionReason);
        entity = evaluationRepository.save(entity);

        if (reviewCase != null) {
            reviewCase.setRiskEvaluation(entity);
            reviewCase.setPriority(priority != null ? priority : "MEDIUM");
            reviewCaseRepository.save(reviewCase);
        }

        publishOutboxEvent(entity, decision);

        // Record this transaction in the customer_activity ledger so future evaluations
        // can use it for point-in-time feature calculations (velocity, stats, novelty, etc.).
        // We record for all decisions so the history is complete and accurate.
        if (req.getCustomerId() != null) {
            String txType = req.getTransactionType() != null ? req.getTransactionType().name() : "PURCHASE";
            String counterparty = "TRANSFER".equalsIgnoreCase(txType) ? req.getRecipientId() : null;
            Double lat  = req.getCustomerLatitude();
            Double lon  = req.getCustomerLongitude();
            customerActivityService.recordActivity(
                    req.getCustomerId(),
                    req.getTransactionId(),
                    txType,
                    req.getAmount(),
                    counterparty,
                    req.getMerchantCategory(),
                    lat,
                    lon,
                    decision.name(),
                    req.getTimestamp() != null ? req.getTimestamp() : java.time.Instant.now()
            );
        }

        return toResponse(entity);
    }

    private RiskEvaluationResponse persistAndOpenReview(RiskEvaluationRequest req,
                                                        DecisionPolicyEntity policy,
                                                        String mlStatus,
                                                        MlPredictionResponse ml,
                                                        BigDecimal probability,
                                                        String decisionReason,
                                                        String priority) {
        ReviewCaseEntity reviewCase = new ReviewCaseEntity();
        reviewCase.setTransactionId(req.getTransactionId());
        reviewCase.setStatus("OPEN");
        reviewCase.setSlaDueAt(Instant.now().plus(Duration.ofHours(reviewSlaDurationHours)));
        return persist(req, policy, mlStatus, ml, probability,
                RiskStatus.PENDING_REVIEW, decisionReason, reviewCase, priority);
    }

    private RiskEvaluationEntity buildEvaluationEntity(RiskEvaluationRequest req,
                                                       DecisionPolicyEntity policy,
                                                       String mlStatus,
                                                       MlPredictionResponse ml,
                                                       BigDecimal probability,
                                                       RiskStatus decision,
                                                       String decisionReason) {
        RiskEvaluationEntity e = new RiskEvaluationEntity();
        e.setTransactionId(req.getTransactionId());
        e.setTransactionType(req.getTransactionType() != null ? req.getTransactionType().name() : "PURCHASE");
        e.setCustomerId(req.getCustomerId());
        e.setPolicy(policy);
        e.setMlStatus(mlStatus);
        e.setDecision(decision.name());
        e.setDecisionReason(decisionReason);
        e.setTraceId(MDC.get("correlationId"));

        e.setFeatureSpecVersion(FEATURE_SPEC_VERSION);
        e.setFeatures(extractSanitizedFeaturesJson(req));

        if (ml != null) {
            e.setModelName(ml.getModelName());
            e.setModelVersion(ml.getModelVersion());
            e.setPredictionLabel(ml.getLabel());  // M2: label replaces prediction
            e.setFraudProbability(probability);
            e.setUncertainty(toBigDecimal(ml.getUncertainty()));
            if (ml.getInferenceMs() != null && ml.getInferenceMs() >= 0) {
                e.setInferenceMs(ml.getInferenceMs());
            }
        }
        return e;
    }

    /**
     * Serializes only the features available and used for evaluation into a JSON string,
     * adhering to feature specification 1.0. Sensitive / protected attributes (e.g. gender)
     * and unnecessary personal identifiable information or secrets are strictly excluded.
     */
    private String extractSanitizedFeaturesJson(RiskEvaluationRequest req) {
        if (req == null) {
            return null;
        }
        Map<String, Object> featureMap = new HashMap<>();
        if (req.getAmount() != null) {
            featureMap.put("amt", req.getAmount());
        }
        if (req.getTimestamp() != null) {
            featureMap.put("trans_date_trans_time", req.getTimestamp().toString());
        }
        if (req.getMerchantCategory() != null) {
            featureMap.put("category", req.getMerchantCategory());
        }
        if (req.getCustomerAge() != null) {
            featureMap.put("customer_age", req.getCustomerAge());
        }
        if (req.getCustomerLatitude() != null) {
            featureMap.put("lat", req.getCustomerLatitude());
        }
        if (req.getCustomerLongitude() != null) {
            featureMap.put("long", req.getCustomerLongitude());
        }
        if (req.getCityPopulation() != null) {
            featureMap.put("city_pop", req.getCityPopulation());
        }
        if (req.getMerchantLatitude() != null) {
            featureMap.put("merch_lat", req.getMerchantLatitude());
        }
        if (req.getMerchantLongitude() != null) {
            featureMap.put("merch_long", req.getMerchantLongitude());
        }

        try {
            return objectMapper.writeValueAsString(featureMap);
        } catch (JsonProcessingException ex) {
            log.warn("[RISK] Failed to serialize sanitized features JSON for transactionId={}: {}",
                    req.getTransactionId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Persists the outbox event for this evaluation.
     *
     * IMPORTANT: Any failure here MUST propagate as a RuntimeException so that the enclosing
     * @Transactional method rolls back. We must never commit an evaluation without its outbox
     * event, as the Transaction Service relies on it to learn the risk decision.
     *
     * @throws IllegalStateException if the payload cannot be serialized (triggers rollback)
     */
    private void publishOutboxEvent(RiskEvaluationEntity entity, RiskStatus decision) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("evaluationId", entity.getId().toString());
        payload.put("transactionId", entity.getTransactionId().toString());
        payload.put("decision", decision.name());
        payload.put("decisionReason", entity.getDecisionReason());
        payload.put("fraudProbability", entity.getFraudProbability());
        payload.put("modelVersion", entity.getModelVersion());
        payload.put("evaluatedAt", entity.getCreatedAt().toString());

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Propagate as RuntimeException to trigger @Transactional rollback.
            // The evaluation must NOT be committed without its outbox event.
            throw new IllegalStateException(
                    "[RISK] Cannot serialize outbox event for evaluationId=" + entity.getId()
                    + "; rolling back transaction to preserve atomicity.", ex);
        }

        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                "RISK_EVALUATION",
                entity.getTransactionId().toString(),
                "RISK_EVALUATION_COMPLETED",
                jsonPayload,
                entity.getTraceId()
        );
        outboxRepository.save(outboxEvent);
    }

    private RiskEvaluationResponse toResponse(RiskEvaluationEntity entity) {
        return new RiskEvaluationResponse(
                entity.getId(),
                entity.getTransactionId(),
                RiskStatus.valueOf(entity.getDecision()),
                entity.getFraudProbability() != null ? entity.getFraudProbability().doubleValue() : null,
                entity.getUncertainty() != null ? entity.getUncertainty().doubleValue() : null,
                entity.getModelVersion(),
                entity.getDecisionReason(),
                entity.getCreatedAt()
        );
    }

    private BigDecimal toBigDecimal(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value);
    }
}
