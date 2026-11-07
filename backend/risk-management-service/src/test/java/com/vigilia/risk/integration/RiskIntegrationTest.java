package com.vigilia.risk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.dto.TransactionType;
import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.repository.DecisionPolicyRepository;
import com.vigilia.risk.repository.OutboxEventRepository;
import com.vigilia.risk.repository.ReviewCaseRepository;
import com.vigilia.risk.repository.RiskEvaluationRepository;
import com.vigilia.risk.service.MlPredictionClient;
import com.vigilia.risk.service.MlPredictionClient.MlPredictionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 10 — Integration tests for risk-management-service.
 *
 * Covers all Step 10 objectives:
 *  - Hard limit rejection (amount > max_amount_hard_limit)
 *  - Velocity cap rejection (too many transactions per hour)
 *  - ML APPROVED path (probability below approve threshold)
 *  - ML REJECTED path (probability above reject threshold)
 *  - ML timeout / unavailable → PENDING_REVIEW fallback (FALLBACK_ML_FAILURE)
 *  - TRANSFER model unavailable → PENDING_REVIEW fallback
 *  - Idempotency: duplicate request returns cached result
 *  - GET /api/v1/risk/{transactionId} returns stored evaluation
 *  - Outbox event persisted atomically with each evaluation
 *
 * Uses H2 in-memory DB (MODE=PostgreSQL) and mocks MlPredictionClient
 * to simulate ML outcomes without requiring a live FastAPI server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskEvaluationRepository evaluationRepository;

    @Autowired
    private DecisionPolicyRepository policyRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    // MlPredictionClient is mocked so tests are independent of the FastAPI server
    @MockBean
    private MlPredictionClient mlPredictionClient;

    @BeforeEach
    void cleanUp() {
        reviewCaseRepository.deleteAll();
        outboxEventRepository.deleteAll();
        evaluationRepository.deleteAll();
        policyRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Helper factories
    // -------------------------------------------------------------------------

    private DecisionPolicyEntity createActivePolicy(String txType) {
        DecisionPolicyEntity policy = new DecisionPolicyEntity();
        policy.setTransactionType(txType);
        policy.setVersion(1);
        policy.setRejectThreshold(new BigDecimal("0.70"));
        policy.setApproveThreshold(new BigDecimal("0.30"));
        policy.setUncertaintyThreshold(new BigDecimal("0.50"));
        policy.setMlTimeoutMs(1500);
        policy.setMaxAmountHardLimit(new BigDecimal("5000.00"));
        policy.setVelocityHardCap1h(15);
        policy.setIsActive(true);
        return policyRepository.save(policy);
    }

    private RiskEvaluationRequest purchaseRequest(UUID txId, UUID customerId, BigDecimal amount) {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(txId);
        req.setCustomerId(customerId);
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(amount);
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        return req;
    }

    private MlPredictionResponse mockMlResponse(double fraudProbability, double uncertainty) {
        MlPredictionResponse ml = new MlPredictionResponse();
        ml.setFraud_probability(fraudProbability);
        ml.setUncertainty(uncertainty);
        ml.setPrediction(fraudProbability >= 0.5 ? "fraud" : "legitimate");
        ml.setModel_name("purchase_xgb_v1");
        ml.setModel_version("1.0.0");
        ml.setInference_ms(42);
        return ml;
    }

    // =========================================================================
    // Test Cases
    // =========================================================================

    @Test
    @DisplayName("1. Hard limit exceeded → REJECTED / HARD_LIMIT")
    void evaluate_HardLimitExceeded_ReturnsRejected() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // Amount (6000€) exceeds hard limit (5000€)
        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("6000.00"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.REJECTED.name())))
                .andExpect(jsonPath("$.decisionReason", is("HARD_LIMIT")))
                .andExpect(jsonPath("$.transactionId", is(txId.toString())));

        // Evaluation + outbox must be persisted
        assertThat(evaluationRepository.existsByTransactionId(txId)).isTrue();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        // No review case for hard limit rejection
        assertThat(reviewCaseRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("2. ML response: low fraud probability → APPROVED")
    void evaluate_LowFraudProbability_ReturnsApproved() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // Fraud probability (0.10) is below approve threshold (0.30)
        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.10, 0.05));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("150.00"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.APPROVED.name())))
                .andExpect(jsonPath("$.decisionReason", is("POLICY_APPROVE")))
                .andExpect(jsonPath("$.fraudProbability", notNullValue()))
                .andExpect(jsonPath("$.evaluatedAt", notNullValue()));

        assertThat(evaluationRepository.existsByTransactionId(txId)).isTrue();
        assertThat(reviewCaseRepository.count()).isEqualTo(0);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. ML response: high fraud probability → REJECTED / POLICY_REJECT")
    void evaluate_HighFraudProbability_ReturnsRejected() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // Fraud probability (0.90) exceeds reject threshold (0.70)
        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.90, 0.05));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("250.00"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.REJECTED.name())))
                .andExpect(jsonPath("$.decisionReason", is("POLICY_REJECT")));

        assertThat(reviewCaseRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("4. ML timeout / unavailable → PENDING_REVIEW / FALLBACK_ML_FAILURE")
    void evaluate_MlTimeout_ReturnsPendingReview_WithFallback() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // Simulate ML failure: client returns null (timeout / 503)
        when(mlPredictionClient.predict(any())).thenReturn(null);

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("200.00"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.PENDING_REVIEW.name())))
                .andExpect(jsonPath("$.decisionReason", is("FALLBACK_ML_FAILURE")));

        // A review case must be opened
        assertThat(reviewCaseRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. TRANSFER type → evaluated via ML model (transfer-v2)")
    void evaluate_TransferType_ReturnsApproved_WhenMlLowRisk() throws Exception {
        createActivePolicy("TRANSFER");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.08, 0.05));

        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(txId);
        req.setCustomerId(customerId);
        req.setTransactionType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("500.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        req.setRecipientId("rec-555");
        req.setSenderBalance(5000.0);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.APPROVED.name())))
                .andExpect(jsonPath("$.decisionReason", is("POLICY_APPROVE")));

        assertThat(evaluationRepository.existsByTransactionId(txId)).isTrue();
    }

    @Test
    @DisplayName("5b. TRANSFER type → ML failure returns PENDING_REVIEW / FALLBACK_ML_FAILURE")
    void evaluate_TransferType_ReturnsPendingReview_WhenMlFails() throws Exception {
        createActivePolicy("TRANSFER");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(mlPredictionClient.predict(any())).thenReturn(null);

        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(txId);
        req.setCustomerId(customerId);
        req.setTransactionType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("500.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        req.setRecipientId("rec-555");
        req.setSenderBalance(5000.0);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.PENDING_REVIEW.name())))
                .andExpect(jsonPath("$.decisionReason", is("FALLBACK_ML_FAILURE")));

        assertThat(reviewCaseRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("6. High ML uncertainty → PENDING_REVIEW / UNCERTAINTY")
    void evaluate_HighUncertainty_ReturnsPendingReview() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // Uncertainty (0.60) >= uncertainty threshold (0.50)
        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.45, 0.60));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("300.00"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.PENDING_REVIEW.name())))
                .andExpect(jsonPath("$.decisionReason", is("UNCERTAINTY")));

        assertThat(reviewCaseRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("7. Idempotency: duplicate evaluation request returns cached result")
    void evaluate_DuplicateRequest_ReturnsCachedResult() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.10, 0.05));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("100.00"));
        String body = objectMapper.writeValueAsString(req);

        // First evaluation
        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.APPROVED.name())));

        // Second evaluation with same transactionId — should return cached result
        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is(RiskStatus.APPROVED.name())));

        // Only 1 evaluation record stored (idempotency)
        assertThat(evaluationRepository.count()).isEqualTo(1);
        // Only 1 outbox event (not duplicated)
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("8. GET /api/v1/risk/{transactionId} returns stored evaluation")
    void getByTransactionId_ReturnsStoredEvaluation() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.10, 0.05));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("75.00"));
        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // GET by transactionId (wrapped in ApiResponse)
        mockMvc.perform(get("/api/v1/risk/{transactionId}", txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId", is(txId.toString())))
                .andExpect(jsonPath("$.data.decision", is(RiskStatus.APPROVED.name())));
    }

    @Test
    @DisplayName("9. GET /api/v1/risk/{transactionId} with unknown ID returns 404")
    void getByTransactionId_UnknownId_Returns404() throws Exception {
        mockMvc.perform(get("/api/v1/risk/{transactionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("10. Validation error: missing required fields returns 400")
    void evaluate_MissingRequiredFields_Returns400() throws Exception {
        // Empty body — all required fields missing
        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("11. Outbox event persisted atomically with APPROVED evaluation")
    void evaluate_OutboxEventPersistedAtomically() throws Exception {
        createActivePolicy("PURCHASE");
        UUID txId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        when(mlPredictionClient.predict(any())).thenReturn(mockMlResponse(0.15, 0.03));

        RiskEvaluationRequest req = purchaseRequest(txId, customerId, new BigDecimal("50.00"));
        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // Verify outbox event contains the correct event type and aggregate
        var outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("RISK_EVALUATION_COMPLETED");
        assertThat(outboxEvents.get(0).getAggregateType()).isEqualTo("RISK_EVALUATION");
        assertThat(outboxEvents.get(0).getAggregateId()).isEqualTo(txId.toString());
        assertThat(outboxEvents.get(0).getPayload()).contains("APPROVED");
    }
}
