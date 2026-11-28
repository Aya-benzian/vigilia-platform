package com.vigilia.risk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.MlExplanationResponse;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.ShapContribution;
import com.vigilia.risk.dto.TransactionType;
import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.entity.RiskEvaluationEntity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SHAP Explainability & JSONB Persistence Integration Tests")
class RiskShapIntegrationTest {

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

    @MockBean
    private MlPredictionClient mlPredictionClient;

    @BeforeEach
    void cleanUp() {
        reviewCaseRepository.deleteAll();
        outboxEventRepository.deleteAll();
        evaluationRepository.deleteAll();
        policyRepository.deleteAll();

        // Seed default PURCHASE policy
        DecisionPolicyEntity purchasePolicy = new DecisionPolicyEntity();
        purchasePolicy.setTransactionType("PURCHASE");
        purchasePolicy.setVersion(1);
        purchasePolicy.setRejectThreshold(new BigDecimal("0.70"));
        purchasePolicy.setApproveThreshold(new BigDecimal("0.30"));
        purchasePolicy.setUncertaintyThreshold(new BigDecimal("0.50"));
        purchasePolicy.setMaxAmountHardLimit(new BigDecimal("5000.00"));
        purchasePolicy.setVelocityHardCap1h(15);
        purchasePolicy.setIsActive(true);
        policyRepository.save(purchasePolicy);

        // Seed default TRANSFER policy
        DecisionPolicyEntity transferPolicy = new DecisionPolicyEntity();
        transferPolicy.setTransactionType("TRANSFER");
        transferPolicy.setVersion(1);
        transferPolicy.setRejectThreshold(new BigDecimal("0.65"));
        transferPolicy.setApproveThreshold(new BigDecimal("0.25"));
        transferPolicy.setUncertaintyThreshold(new BigDecimal("0.50"));
        transferPolicy.setMaxAmountHardLimit(new BigDecimal("10000.00"));
        transferPolicy.setVelocityHardCap1h(10);
        transferPolicy.setIsActive(true);
        policyRepository.save(transferPolicy);
    }

    private RiskEvaluationRequest buildPurchaseRequest(UUID txId) {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(txId);
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(new BigDecimal("120.50"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        req.setMerchantCategory("groceries");
        req.setCustomerLatitude(48.8566);
        req.setCustomerLongitude(2.3522);
        req.setMerchantLatitude(48.8570);
        req.setMerchantLongitude(2.3530);
        return req;
    }

    private RiskEvaluationRequest buildTransferRequest(UUID txId) {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(txId);
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("300.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        req.setRecipientId(UUID.randomUUID().toString());
        req.setSenderBalance(1500.0);
        return req;
    }

    @Test
    @DisplayName("PURCHASE: Successful SHAP call persists contributions in PostgreSQL JSONB column without double serialization")
    void purchase_withSuccessfulShap_persistsJsonbContributions() throws Exception {
        UUID txId = UUID.randomUUID();
        RiskEvaluationRequest req = buildPurchaseRequest(txId);

        MlPredictionResponse mlResponse = new MlPredictionResponse();
        mlResponse.setRequestId(txId.toString());
        mlResponse.setLabel("LEGITIMATE");
        mlResponse.setFraudProbability(0.04);
        mlResponse.setUncertainty(0.02);
        mlResponse.setModelName("purchase-fraud-model");
        mlResponse.setModelVersion("1");
        mlResponse.setFeatureSpecVersion("purchase-v2");
        mlResponse.setInferenceMs(15);
        mlResponse.setFeatures(Map.of("amount", 120.50, "hour_of_day", 14));

        MlExplanationResponse shapResponse = new MlExplanationResponse(
                txId.toString(), "purchase-fraud-model", "1", 0.045,
                List.of(
                        new ShapContribution("amount", 120.50, 0.25),
                        new ShapContribution("distance_home_merchant_km", 0.15, -0.10)
                )
        );

        when(mlPredictionClient.predict(any(RiskEvaluationRequest.class))).thenReturn(mlResponse);
        when(mlPredictionClient.explain(any(RiskEvaluationRequest.class), eq(mlResponse))).thenReturn(shapResponse);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        // Verify entity persisted in DB
        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(txId).orElse(null);
        assertThat(entity).isNotNull();
        assertThat(entity.getDecision()).isEqualTo("APPROVED");
        assertThat(entity.getFeatureSpecVersion()).isEqualTo("purchase-v2");

        // Verify JSONB persistence of shap_values
        List<ShapContribution> savedShap = entity.getShapValues();
        assertThat(savedShap).isNotNull().hasSize(2);
        assertThat(savedShap.get(0).getFeature()).isEqualTo("amount");
        assertThat(savedShap.get(0).getShap()).isEqualTo(0.25);
        assertThat(savedShap.get(1).getFeature()).isEqualTo("distance_home_merchant_km");
        assertThat(savedShap.get(1).getShap()).isEqualTo(-0.10);
    }

    @Test
    @DisplayName("TRANSFER: Successful SHAP call persists transfer-v2 contributions")
    void transfer_withSuccessfulShap_persistsContributions() throws Exception {
        UUID txId = UUID.randomUUID();
        RiskEvaluationRequest req = buildTransferRequest(txId);

        MlPredictionResponse mlResponse = new MlPredictionResponse();
        mlResponse.setRequestId(txId.toString());
        mlResponse.setLabel("LEGITIMATE");
        mlResponse.setFraudProbability(0.08);
        mlResponse.setModelName("transfer-fraud-model");
        mlResponse.setModelVersion("1");
        mlResponse.setFeatureSpecVersion("transfer-v2");
        mlResponse.setFeatures(Map.of("amount", 300.0, "dest_risk_score", 0.5));

        MlExplanationResponse shapResponse = new MlExplanationResponse(
                txId.toString(), "transfer-fraud-model", "1", 0.05,
                List.of(
                        new ShapContribution("amount", 300.0, 0.18),
                        new ShapContribution("dest_risk_score", 0.5, 0.22)
                )
        );

        when(mlPredictionClient.predict(any(RiskEvaluationRequest.class))).thenReturn(mlResponse);
        when(mlPredictionClient.explain(any(RiskEvaluationRequest.class), eq(mlResponse))).thenReturn(shapResponse);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(txId).orElse(null);
        assertThat(entity).isNotNull();
        assertThat(entity.getFeatureSpecVersion()).isEqualTo("transfer-v2");
        assertThat(entity.getShapValues()).hasSize(2);
    }

    @Test
    @DisplayName("SHAP Resilience: HTTP 503 from SHAP leaves prediction intact and persists shap_values = null")
    void resilience_whenShapUnavailable503_decisionPreservedAndShapNull() throws Exception {
        UUID txId = UUID.randomUUID();
        RiskEvaluationRequest req = buildPurchaseRequest(txId);

        MlPredictionResponse mlResponse = new MlPredictionResponse();
        mlResponse.setRequestId(txId.toString());
        mlResponse.setLabel("LEGITIMATE");
        mlResponse.setFraudProbability(0.05);
        mlResponse.setModelName("purchase-fraud-model");
        mlResponse.setModelVersion("1");
        mlResponse.setFeatureSpecVersion("purchase-v2");

        // explain returns null on 503 (handled gracefully by MlPredictionClient)
        when(mlPredictionClient.predict(any(RiskEvaluationRequest.class))).thenReturn(mlResponse);
        when(mlPredictionClient.explain(any(RiskEvaluationRequest.class), eq(mlResponse))).thenReturn(null);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(txId).orElse(null);
        assertThat(entity).isNotNull();
        assertThat(entity.getDecision()).isEqualTo("APPROVED");
        assertThat(entity.getFraudProbability()).isEqualByComparingTo(new BigDecimal("0.05"));
        assertThat(entity.getShapValues()).isNull(); // Gracefully null!
    }

    @Test
    @DisplayName("SHAP Resilience: SHAP timeout leaves prediction intact and persists shap_values = null")
    void resilience_whenShapTimesOut_decisionPreservedAndShapNull() throws Exception {
        UUID txId = UUID.randomUUID();
        RiskEvaluationRequest req = buildPurchaseRequest(txId);

        MlPredictionResponse mlResponse = new MlPredictionResponse();
        mlResponse.setRequestId(txId.toString());
        mlResponse.setLabel("FRAUD");
        mlResponse.setFraudProbability(0.95);
        mlResponse.setModelName("purchase-fraud-model");
        mlResponse.setModelVersion("1");
        mlResponse.setFeatureSpecVersion("purchase-v2");

        when(mlPredictionClient.predict(any(RiskEvaluationRequest.class))).thenReturn(mlResponse);
        when(mlPredictionClient.explain(any(RiskEvaluationRequest.class), eq(mlResponse)))
                .thenThrow(new RuntimeException("Simulated socket timeout"));

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"));

        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(txId).orElse(null);
        assertThat(entity).isNotNull();
        assertThat(entity.getDecision()).isEqualTo("REJECTED");
        assertThat(entity.getDecisionReason()).isEqualTo("POLICY_REJECT");
        assertThat(entity.getShapValues()).isNull();
    }

    @Test
    @DisplayName("SHAP Correlation: requestId mismatch results in shap_values = null")
    void correlation_whenRequestIdMismatches_shapValuesDisregarded() throws Exception {
        UUID txId = UUID.randomUUID();
        RiskEvaluationRequest req = buildPurchaseRequest(txId);

        MlPredictionResponse mlResponse = new MlPredictionResponse();
        mlResponse.setRequestId(txId.toString());
        mlResponse.setLabel("LEGITIMATE");
        mlResponse.setFraudProbability(0.10);
        mlResponse.setModelName("purchase-fraud-model");

        // MlPredictionClient returns null when correlation mismatch is detected
        when(mlPredictionClient.predict(any(RiskEvaluationRequest.class))).thenReturn(mlResponse);
        when(mlPredictionClient.explain(any(RiskEvaluationRequest.class), eq(mlResponse))).thenReturn(null);

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        RiskEvaluationEntity entity = evaluationRepository.findByTransactionId(txId).orElse(null);
        assertThat(entity).isNotNull();
        assertThat(entity.getShapValues()).isNull();
    }
}
