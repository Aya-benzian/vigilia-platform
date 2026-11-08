package com.vigilia.transaction.client;

import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.exception.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RiskEvaluationClientImplTest {

    private final UUID txnId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();

    @Test
    @DisplayName("Risk Evaluation - Succès avec décision APPROVED")
    void evaluate_Success_Approved() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RiskEvaluationClientImpl client = new RiskEvaluationClientImpl(builder, "http://localhost:8084", true);

        String jsonResponse = """
                {
                  "evaluationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "transactionId": "%s",
                  "decision": "APPROVED",
                  "fraudProbability": 0.05,
                  "decisionReason": "Transaction normale",
                  "evaluatedAt": "2026-10-26T14:00:00Z"
                }
                """.formatted(txnId);

        server.expect(requestTo("http://localhost:8084/api/v1/risk/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        RiskEvaluationRequest req = new RiskEvaluationRequest(txnId, customerId, TransactionType.PURCHASE, new BigDecimal("25.00"), "EUR", Instant.now());
        RiskEvaluationResponse resp = client.evaluate(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getDecision()).isEqualTo(RiskStatus.APPROVED);
        assertThat(resp.getFraudProbability()).isEqualTo(0.05);
        server.verify();
    }

    @Test
    @DisplayName("Risk Evaluation - Échec HTTP avec repli sécurisé PENDING_REVIEW (ne jamais approuver automatiquement)")
    void evaluate_HttpError_FallbackToPendingReview() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RiskEvaluationClientImpl client = new RiskEvaluationClientImpl(builder, "http://localhost:8084", true);

        server.expect(requestTo("http://localhost:8084/api/v1/risk/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        RiskEvaluationRequest req = new RiskEvaluationRequest(txnId, customerId, TransactionType.PURCHASE, new BigDecimal("100.00"), "EUR", Instant.now());
        RiskEvaluationResponse resp = client.evaluate(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getDecision()).isEqualTo(RiskStatus.PENDING_REVIEW);
        assertThat(resp.getDecisionReason()).contains("RISK_SERVICE_UNAVAILABLE_FALLBACK");
        server.verify();
    }

    @Test
    @DisplayName("Risk Evaluation - Échec HTTP avec repli désactivé : lève ServiceUnavailableException (503)")
    void evaluate_HttpError_FallbackDisabled_ThrowsServiceUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RiskEvaluationClientImpl client = new RiskEvaluationClientImpl(builder, "http://localhost:8084", false);

        server.expect(requestTo("http://localhost:8084/api/v1/risk/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        RiskEvaluationRequest req = new RiskEvaluationRequest(txnId, customerId, TransactionType.PURCHASE, new BigDecimal("100.00"), "EUR", Instant.now());

        assertThatThrownBy(() -> client.evaluate(req))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Risk Management Service est indisponible");

        server.verify();
    }

    @Test
    @DisplayName("Risk Evaluation - Contrat interservices : désérialisation complète du JSON plat conforme OpenAPI")
    void evaluate_ContractCompliantFlatJson_DeserializesAllFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RiskEvaluationClientImpl client = new RiskEvaluationClientImpl(builder, "http://localhost:8084", true);

        UUID evalId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        Instant evalTime = Instant.parse("2026-10-26T14:00:00Z");

        String jsonResponse = """
                {
                  "evaluationId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "transactionId": "%s",
                  "decision": "APPROVED",
                  "fraudProbability": 0.04,
                  "uncertainty": 0.01,
                  "decisionReason": "POLICY_APPROVE",
                  "evaluatedAt": "2026-10-26T14:00:00Z"
                }
                """.formatted(txnId);

        server.expect(requestTo("http://localhost:8084/api/v1/risk/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        RiskEvaluationRequest req = new RiskEvaluationRequest(txnId, customerId, TransactionType.PURCHASE, new BigDecimal("25.00"), "EUR", Instant.now());
        RiskEvaluationResponse resp = client.evaluate(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getDecision()).isNotNull().isEqualTo(RiskStatus.APPROVED);
        assertThat(resp.getEvaluationId()).isEqualTo(evalId);
        assertThat(resp.getTransactionId()).isEqualTo(txnId);
        assertThat(resp.getFraudProbability()).isEqualTo(0.04);
        assertThat(resp.getUncertainty()).isEqualTo(0.01);
        assertThat(resp.getDecisionReason()).isEqualTo("POLICY_APPROVE");
        assertThat(resp.getEvaluatedAt()).isEqualTo(evalTime);
        server.verify();
    }
}
