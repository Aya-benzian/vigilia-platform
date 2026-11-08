package com.vigilia.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.RiskEvaluationResponse;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.dto.TransactionType;
import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.entity.RiskEvaluationEntity;
import com.vigilia.risk.repository.DecisionPolicyRepository;
import com.vigilia.risk.repository.OutboxEventRepository;
import com.vigilia.risk.repository.ReviewCaseRepository;
import com.vigilia.risk.repository.RiskEvaluationRepository;
import com.vigilia.risk.service.MlPredictionClient.MlPredictionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskEvaluationService unit tests")
class RiskEvaluationServiceTest {

    @Mock private RiskEvaluationRepository evaluationRepository;
    @Mock private DecisionPolicyRepository policyRepository;
    @Mock private ReviewCaseRepository reviewCaseRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private CustomerActivityService customerActivityService;
    @Mock private MlPredictionClient mlClient;

    private RiskEvaluationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new RiskEvaluationService(
                evaluationRepository, policyRepository, reviewCaseRepository,
                outboxRepository, customerActivityService, mlClient, objectMapper);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RiskEvaluationRequest buildRequest(BigDecimal amount) {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(amount);
        req.setCurrency("USD");
        req.setTimestamp(Instant.now());
        req.setCustomerAge(35);
        req.setCustomerGender("M");
        req.setCustomerLatitude(40.71);
        req.setCustomerLongitude(-74.01);
        req.setCityPopulation(8000000);
        req.setMerchantCategory("shopping_net");
        req.setMerchantLatitude(40.71);
        req.setMerchantLongitude(-74.00);
        return req;
    }

    private DecisionPolicyEntity buildPolicy() {
        DecisionPolicyEntity p = new DecisionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setTransactionType("PURCHASE");
        p.setRejectThreshold(new BigDecimal("0.70"));
        p.setApproveThreshold(new BigDecimal("0.30"));
        p.setUncertaintyThreshold(new BigDecimal("0.50"));
        p.setMaxAmountHardLimit(new BigDecimal("5000.00"));
        p.setVelocityHardCap1h(15);
        p.setIsActive(true);
        return p;
    }

    private MlPredictionResponse buildMlResponse(double probability, double uncertainty) {
        MlPredictionResponse ml = new MlPredictionResponse();
        ml.setModelName("purchase-fraud-model");
        ml.setModelVersion("1.0.0");
        ml.setFraudProbability(probability);
        ml.setUncertainty(uncertainty);
        ml.setLabel(probability >= 0.5 ? "FRAUD" : "LEGITIMATE");
        ml.setInferenceMs(150);
        return ml;
    }

    private RiskEvaluationEntity savedEntity(RiskEvaluationRequest req, String decision, String reason) {
        RiskEvaluationEntity e = new RiskEvaluationEntity();
        e.setId(UUID.randomUUID());
        e.setTransactionId(req.getTransactionId());
        e.setTransactionType("PURCHASE");
        e.setDecision(decision);
        e.setDecisionReason(reason);
        e.setMlStatus("SUCCESS");
        e.setCreatedAt(Instant.now());
        return e;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Idempotency: returns cached result when evaluation already exists")
    void evaluate_idempotent_returnsExistingResult() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("100.00"));
        RiskEvaluationEntity existing = savedEntity(req, "APPROVED", "POLICY_APPROVE");

        when(evaluationRepository.existsByTransactionId(req.getTransactionId())).thenReturn(true);
        when(evaluationRepository.findByTransactionId(req.getTransactionId()))
                .thenReturn(Optional.of(existing));

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.APPROVED);
        verify(mlClient, never()).predict(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Hard limit: amount > 5000 → REJECTED / HARD_LIMIT")
    void evaluate_hardLimit_rejects() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("10000.00"));
        DecisionPolicyEntity policy = buildPolicy();

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.REJECTED);
        assertThat(response.getDecisionReason()).isEqualTo("HARD_LIMIT");
        verify(mlClient, never()).predict(any());
        verify(reviewCaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Velocity cap: too many transactions in 1h → REJECTED / VELOCITY_EXCEEDED")
    void evaluate_velocityCap_rejects() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("50.00"));
        DecisionPolicyEntity policy = buildPolicy();

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(req.getCustomerId())).thenReturn(20L);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.REJECTED);
        assertThat(response.getDecisionReason()).isEqualTo("VELOCITY_EXCEEDED");
        verify(mlClient, never()).predict(any());
    }

    @Test
    @DisplayName("ML failure → PENDING_REVIEW / FALLBACK_ML_FAILURE (never APPROVED)")
    void evaluate_mlFailure_pendingReview() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("200.00"));
        DecisionPolicyEntity policy = buildPolicy();

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(null);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(reviewCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.PENDING_REVIEW);
        assertThat(response.getDecisionReason()).isEqualTo("FALLBACK_ML_FAILURE");
        verify(reviewCaseRepository).save(any());
    }

    @Test
    @DisplayName("High uncertainty → PENDING_REVIEW / UNCERTAINTY")
    void evaluate_highUncertainty_pendingReview() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("300.00"));
        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.45, 0.80); // uncertainty > 0.50 threshold

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(reviewCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.PENDING_REVIEW);
        assertThat(response.getDecisionReason()).isEqualTo("UNCERTAINTY");
    }

    @Test
    @DisplayName("High probability → REJECTED / POLICY_REJECT")
    void evaluate_highProbability_rejected() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("150.00"));
        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.85, 0.10); // above rejectThreshold=0.70

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setFraudProbability(BigDecimal.valueOf(ml.getFraud_probability()));
            e.setUncertainty(BigDecimal.valueOf(ml.getUncertainty()));
            e.setModelVersion(ml.getModel_version());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.REJECTED);
        assertThat(response.getDecisionReason()).isEqualTo("POLICY_REJECT");
        verify(reviewCaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Low probability → APPROVED / POLICY_APPROVE")
    void evaluate_lowProbability_approved() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("50.00"));
        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.05, 0.10); // below approveThreshold=0.30

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setFraudProbability(BigDecimal.valueOf(ml.getFraud_probability()));
            e.setUncertainty(BigDecimal.valueOf(ml.getUncertainty()));
            e.setModelVersion(ml.getModel_version());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.APPROVED);
        assertThat(response.getDecisionReason()).isEqualTo("POLICY_APPROVE");
        verify(reviewCaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ambiguous zone (between thresholds) → PENDING_REVIEW / AMBIGUOUS_ZONE")
    void evaluate_ambiguousZone_pendingReview() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("100.00"));
        DecisionPolicyEntity policy = buildPolicy();
        // 0.50 is between approveThreshold=0.30 and rejectThreshold=0.70 and uncertainty < 0.50
        MlPredictionResponse ml = buildMlResponse(0.50, 0.20);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(reviewCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.PENDING_REVIEW);
        assertThat(response.getDecisionReason()).isEqualTo("AMBIGUOUS_ZONE");
    }

    @Test
    @DisplayName("Outbox event is published after each evaluation")
    void evaluate_publishesOutboxEvent() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("50.00"));
        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.05, 0.10);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setFraudProbability(BigDecimal.valueOf(ml.getFraud_probability()));
            e.setUncertainty(BigDecimal.valueOf(ml.getUncertainty()));
            e.setModelVersion(ml.getModel_version());
            e.setCreatedAt(Instant.now());
            return e;
        });

        service.evaluate(req);

        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("No active policy: uses hardcoded defaults (30/70/50 thresholds)")
    void evaluate_noPolicyFallsBackToDefaults() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("100.00"));
        MlPredictionResponse ml = buildMlResponse(0.05, 0.10);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.empty());
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setFraudProbability(BigDecimal.valueOf(0.05));
            e.setUncertainty(BigDecimal.valueOf(0.10));
            e.setModelVersion("1.0.0");
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        // 0.05 < 0.30 (default approve threshold) → APPROVED
        assertThat(response.getDecision()).isEqualTo(RiskStatus.APPROVED);
    }

    @Test
    @DisplayName("E6: Outbox serialization failure throws IllegalStateException and never persists outbox")
    void evaluate_outboxSerializationFailure_throwsIllegalStateException() throws Exception {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("50.00"));
        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.05, 0.10);

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("Serialization error") {});

        RiskEvaluationService serviceWithFailingMapper = new RiskEvaluationService(
                evaluationRepository, policyRepository, reviewCaseRepository,
                outboxRepository, customerActivityService, mlClient, failingMapper);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        assertThatThrownBy(() -> serviceWithFailingMapper.evaluate(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot serialize outbox event");

        // Atomicity guarantee: outbox event is never persisted if serialization failed
        verify(outboxRepository, never()).save(any());
    }

    // =========================================================================
    // Phase 1 (E2, E5) and Phase 2 (E4) tests
    // =========================================================================

    @Test
    @DisplayName("E4: TRANSFER calls ML model (transfer-v2), evaluates fraud probability, and writes audit/outbox")
    void evaluate_transfer_callsMl_andEvaluatesNormally() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("150.00"));
        req.setTransactionType(TransactionType.TRANSFER);
        req.setRecipientId("rec-999");
        req.setSenderBalance(1000.0);

        DecisionPolicyEntity policy = buildPolicy();
        policy.setTransactionType("TRANSFER");

        MlPredictionResponse ml = buildMlResponse(0.05, 0.05);
        ml.setModelName("transfer-fraud-model");

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("TRANSFER"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(1L);
        when(mlClient.predict(any())).thenReturn(ml);

        ArgumentCaptor<RiskEvaluationEntity> evalCaptor = ArgumentCaptor.forClass(RiskEvaluationEntity.class);
        when(evaluationRepository.save(evalCaptor.capture())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        // 1. Un TRANSFER déclenche maintenant un appel ML
        verify(mlClient).predict(req);

        // 2. Avec probabilité faible, la décision est APPROVED
        assertThat(response.getDecision()).isEqualTo(RiskStatus.APPROVED);
        assertThat(response.getDecisionReason()).isEqualTo("POLICY_APPROVE");

        // 3. Écritures d'audit et d'outbox cohérentes
        verify(outboxRepository).save(any());

        RiskEvaluationEntity saved = evalCaptor.getValue();
        assertThat(saved.getTransactionType()).isEqualTo("TRANSFER");
        assertThat(saved.getMlStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getDecision()).isEqualTo(RiskStatus.APPROVED.name());
        assertThat(saved.getDecisionReason()).isEqualTo("POLICY_APPROVE");
    }

    @Test
    @DisplayName("E4: TRANSFER ML failure falls back to PENDING_REVIEW and opens review case")
    void evaluate_transfer_mlFailure_fallsBackToPendingReview() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("150.00"));
        req.setTransactionType(TransactionType.TRANSFER);

        DecisionPolicyEntity policy = buildPolicy();
        policy.setTransactionType("TRANSFER");

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("TRANSFER"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(1L);
        when(mlClient.predict(any())).thenReturn(null);

        ArgumentCaptor<RiskEvaluationEntity> evalCaptor = ArgumentCaptor.forClass(RiskEvaluationEntity.class);
        when(evaluationRepository.save(evalCaptor.capture())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(reviewCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RiskEvaluationResponse response = service.evaluate(req);

        verify(mlClient).predict(req);
        assertThat(response.getDecision()).isEqualTo(RiskStatus.PENDING_REVIEW);
        assertThat(response.getDecisionReason()).isEqualTo("FALLBACK_ML_FAILURE");
        verify(reviewCaseRepository).save(any());
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("E4: TRANSFER hard limit check is applied before fallback")
    void evaluate_transfer_hardLimitExceeded_rejectsWithoutMl() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("9999.00"));
        req.setTransactionType(TransactionType.TRANSFER);

        DecisionPolicyEntity policy = buildPolicy();
        policy.setTransactionType("TRANSFER");
        policy.setMaxAmountHardLimit(new BigDecimal("5000.00"));

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("TRANSFER"))
                .thenReturn(Optional.of(policy));
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.REJECTED);
        assertThat(response.getDecisionReason()).isEqualTo("HARD_LIMIT");
        verify(mlClient, never()).predict(any());
        verify(reviewCaseRepository, never()).save(any());
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("E4: TRANSFER velocity cap check is applied before fallback")
    void evaluate_transfer_velocityExceeded_rejectsWithoutMl() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("200.00"));
        req.setTransactionType(TransactionType.TRANSFER);

        DecisionPolicyEntity policy = buildPolicy();
        policy.setTransactionType("TRANSFER");
        policy.setVelocityHardCap1h(10);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("TRANSFER"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(req.getCustomerId())).thenReturn(15L);
        when(evaluationRepository.save(any())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.REJECTED);
        assertThat(response.getDecisionReason()).isEqualTo("VELOCITY_EXCEEDED");
        verify(mlClient, never()).predict(any());
        verify(reviewCaseRepository, never()).save(any());
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("E2: Persists feature_spec_version = 1.0 and sanitized features JSON excluding gender")
    void evaluate_e2_persistsFeatureSpecVersionAndSanitizedFeaturesWithoutGender() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("80.00"));
        req.setCustomerGender("F"); // Must NOT be persisted in features JSON
        req.setCustomerAge(42);
        req.setMerchantCategory("groceries");
        req.setCityPopulation(250000);

        DecisionPolicyEntity policy = buildPolicy();
        MlPredictionResponse ml = buildMlResponse(0.05, 0.05);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(ml);

        ArgumentCaptor<RiskEvaluationEntity> evalCaptor = ArgumentCaptor.forClass(RiskEvaluationEntity.class);
        when(evaluationRepository.save(evalCaptor.capture())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        RiskEvaluationResponse response = service.evaluate(req);

        assertThat(response.getDecision()).isEqualTo(RiskStatus.APPROVED);

        RiskEvaluationEntity saved = evalCaptor.getValue();
        assertThat(saved.getFeatureSpecVersion()).isEqualTo("1.0");
        assertThat(saved.getFeatures()).isNotNull();

        // Verify features JSON contains expected evaluation features
        assertThat(saved.getFeatures()).contains("\"amt\":80.00");
        assertThat(saved.getFeatures()).contains("\"category\":\"groceries\"");
        assertThat(saved.getFeatures()).contains("\"customer_age\":42");
        assertThat(saved.getFeatures()).contains("\"city_pop\":250000");

        // Verify gender and protected/unnecessary fields are strictly excluded
        assertThat(saved.getFeatures()).doesNotContain("gender");
        assertThat(saved.getFeatures()).doesNotContain("customerGender");
    }

    @Test
    @DisplayName("E5: Validates and persists inference_ms as Integer; ignores negative values")
    void evaluate_e5_inferenceMsValidatedAndPersistedAsInteger() {
        RiskEvaluationRequest req = buildRequest(new BigDecimal("70.00"));
        DecisionPolicyEntity policy = buildPolicy();

        // Valid positive inference_ms
        MlPredictionResponse mlValid = buildMlResponse(0.05, 0.05);
        mlValid.setInference_ms(85);

        when(evaluationRepository.existsByTransactionId(any())).thenReturn(false);
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));
        when(customerActivityService.countTransactionsInLastHour(any())).thenReturn(0L);
        when(mlClient.predict(any())).thenReturn(mlValid);

        ArgumentCaptor<RiskEvaluationEntity> evalCaptor = ArgumentCaptor.forClass(RiskEvaluationEntity.class);
        when(evaluationRepository.save(evalCaptor.capture())).thenAnswer(inv -> {
            RiskEvaluationEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(Instant.now());
            return e;
        });

        service.evaluate(req);

        RiskEvaluationEntity saved = evalCaptor.getValue();
        assertThat(saved.getInferenceMs()).isEqualTo(85);
        assertThat(saved.getInferenceMs()).isInstanceOf(Integer.class);

        // Invalid negative inference_ms
        RiskEvaluationRequest reqNegative = buildRequest(new BigDecimal("75.00"));
        MlPredictionResponse mlNegative = buildMlResponse(0.05, 0.05);
        mlNegative.setInference_ms(-10);

        when(mlClient.predict(any())).thenReturn(mlNegative);

        service.evaluate(reqNegative);

        RiskEvaluationEntity savedNegative = evalCaptor.getValue();
        assertThat(savedNegative.getInferenceMs()).isNull();
    }
}
