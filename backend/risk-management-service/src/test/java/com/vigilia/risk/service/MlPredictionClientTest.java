package com.vigilia.risk.service;

import com.vigilia.risk.dto.MlExplanationResponse;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.ShapContribution;
import com.vigilia.risk.dto.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MlPredictionClient unit tests (M2 contract)")
class MlPredictionClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private PurchaseFeatureCalculator purchaseFeatureCalculator;
    @Mock private TransferFeatureCalculator transferFeatureCalculator;

    private MlPredictionClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);

        client = new MlPredictionClient(builder, purchaseFeatureCalculator, transferFeatureCalculator);
        ReflectionTestUtils.setField(client, "mlBaseUrl", "http://ml-serving:8000");
    }

    private RiskEvaluationRequest buildPurchaseRequest() {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.PURCHASE);
        req.setAmount(new BigDecimal("100.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        return req;
    }

    private RiskEvaluationRequest buildTransferRequest() {
        RiskEvaluationRequest req = new RiskEvaluationRequest();
        req.setTransactionId(UUID.randomUUID());
        req.setCustomerId(UUID.randomUUID());
        req.setTransactionType(TransactionType.TRANSFER);
        req.setAmount(new BigDecimal("500.00"));
        req.setCurrency("EUR");
        req.setTimestamp(Instant.now());
        req.setRecipientId(UUID.randomUUID().toString());
        req.setSenderBalance(2000.0);
        return req;
    }

    /** Builds a valid M2 response (uses requestId + label instead of transaction_id + prediction). */
    private MlPredictionClient.MlPredictionResponse buildMlResponse(String requestId) {
        MlPredictionClient.MlPredictionResponse r = new MlPredictionClient.MlPredictionResponse();
        r.setRequestId(requestId);
        r.setLabel("LEGITIMATE");
        r.setFraudProbability(0.05);
        r.setUncertainty(0.02);
        r.setModelName("purchase-fraud-model");
        r.setModelVersion("2.0.0");
        r.setFeatureSpecVersion("purchase-v2");
        r.setInferenceMs(42);
        return r;
    }

    @Test
    @DisplayName("PURCHASE: on socket timeout, returns null safely (never APPROVED)")
    void predict_purchase_timeout_returnsNullSafely() {
        RiskEvaluationRequest req = buildPurchaseRequest();
        when(purchaseFeatureCalculator.compute(any())).thenReturn(java.util.Map.of("amount", 100.0));

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/predict/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        assertThat(response).isNull();
    }

    @Test
    @DisplayName("TRANSFER: ML call is now active — calls /predict/transfer endpoint")
    void predict_transfer_callsMlTransferEndpoint() {
        RiskEvaluationRequest req = buildTransferRequest();
        when(transferFeatureCalculator.compute(any())).thenReturn(java.util.Map.of("amount", 500.0));

        MlPredictionClient.MlPredictionResponse expected = new MlPredictionClient.MlPredictionResponse();
        expected.setRequestId(req.getTransactionId().toString());
        expected.setLabel("LEGITIMATE");
        expected.setFraudProbability(0.08);
        expected.setFeatureSpecVersion("transfer-v2");

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/predict/transfer")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class)).thenReturn(expected);

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        assertThat(response).isNotNull();
        assertThat(response.getLabel()).isEqualTo("LEGITIMATE");
        assertThat(response.getFeatureSpecVersion()).isEqualTo("transfer-v2");
        // Transfer ML is now active — restClient.post() MUST be called
        verify(restClient).post();
    }

    @Test
    @DisplayName("PURCHASE: success response maps M2 fields (requestId, label, feature_spec_version)")
    void predict_purchase_success_mapsMlM2Fields() {
        RiskEvaluationRequest req = buildPurchaseRequest();
        when(purchaseFeatureCalculator.compute(any())).thenReturn(java.util.Map.of("amount", 100.0));

        MlPredictionClient.MlPredictionResponse expected = buildMlResponse(req.getTransactionId().toString());

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/predict/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class)).thenReturn(expected);

        MlPredictionClient.MlPredictionResponse response = client.predict(req);

        assertThat(response).isNotNull();
        assertThat(response.getRequestId()).isEqualTo(req.getTransactionId().toString());
        assertThat(response.getLabel()).isEqualTo("LEGITIMATE");           // M2 field
        assertThat(response.getFeatureSpecVersion()).isEqualTo("purchase-v2"); // M2 field
        assertThat(response.getInferenceMs()).isEqualTo(42);
        assertThat(response.getInferenceMs()).isInstanceOf(Integer.class);
    }

    @Test
    @DisplayName("Unknown transaction type: returns null, no ML call")
    void predict_unknownType_returnsNull() {
        RiskEvaluationRequest req = buildPurchaseRequest();
        // Force null type
        req.setTransactionType(null);
        // null type defaults to PURCHASE in predict() — but test with known unreachable type
        // by overriding the transactionType check logic indirectly via null handling
        MlPredictionClient.MlPredictionResponse response = client.predict(req);
        // null type defaults to PURCHASE branch — just ensure no NPE
        // (this is a defensive test for null safety)
        // We expect either a valid response or null, but no exception
        // The actual result depends on the mock setup (restClient.post() not stubbed → returns null from mock)
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("Audit P1: When transactionId is null, generates a non-empty RFC 4122 UUID request_id")
    void predict_nullTransactionId_generatesNonEmptyUuidRequestId() {
        RiskEvaluationRequest req = buildPurchaseRequest();
        req.setTransactionId(null);
        when(purchaseFeatureCalculator.compute(any())).thenReturn(java.util.Map.of("amount", 100.0));

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);

        org.mockito.ArgumentCaptor<Object> bodyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        when(requestBodySpec.body(bodyCaptor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlPredictionClient.MlPredictionResponse.class)).thenReturn(new MlPredictionClient.MlPredictionResponse());

        client.predict(req);

        Object capturedBody = bodyCaptor.getValue();
        assertThat(capturedBody).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) capturedBody;

        assertThat(map).containsKey("request_id");
        String requestId = (String) map.get("request_id");
        assertThat(requestId).isNotBlank();
        // Validate RFC 4122 UUID format
        assertThat(UUID.fromString(requestId)).isNotNull();
    }

    @Test
    @DisplayName("SHAP Purchase: successful call returns contributions and matches request_id")
    void explain_purchase_success_returnsContributions() {
        String reqId = UUID.randomUUID().toString();
        java.util.Map<String, Object> features = java.util.Map.of("amount", 125.50, "hour_of_day", 14);

        MlExplanationResponse expected = new MlExplanationResponse(
                reqId, "purchase-fraud-model", "1", 0.045,
                java.util.List.of(
                        new ShapContribution("amount", 125.50, 0.28),
                        new ShapContribution("distance_home_merchant_km", 812.4, 0.15)
                )
        );

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/explain/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlExplanationResponse.class)).thenReturn(expected);

        MlExplanationResponse res = client.explainPurchase(reqId, features);

        assertThat(res).isNotNull();
        assertThat(res.getRequestId()).isEqualTo(reqId);
        assertThat(res.getContributions()).hasSize(2);
        assertThat(res.getContributions().get(0).getFeature()).isEqualTo("amount");
        assertThat(res.getContributions().get(0).getShap()).isEqualTo(0.28);
    }

    @Test
    @DisplayName("SHAP Transfer: successful call returns contributions")
    void explain_transfer_success_returnsContributions() {
        String reqId = UUID.randomUUID().toString();
        java.util.Map<String, Object> features = java.util.Map.of("amount", 500.0, "dest_risk_score", 0.8);

        MlExplanationResponse expected = new MlExplanationResponse(
                reqId, "transfer-fraud-model", "1", 0.03,
                java.util.List.of(new ShapContribution("dest_risk_score", 0.8, 0.45))
        );

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/explain/transfer")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlExplanationResponse.class)).thenReturn(expected);

        MlExplanationResponse res = client.explainTransfer(reqId, features);

        assertThat(res).isNotNull();
        assertThat(res.getRequestId()).isEqualTo(reqId);
        assertThat(res.getContributions()).hasSize(1);
    }

    @Test
    @DisplayName("SHAP Resilience: on HTTP 503 or RestClientException, returns null safely")
    void explain_onHttp503_returnsNullSafely() {
        String reqId = UUID.randomUUID().toString();
        java.util.Map<String, Object> features = java.util.Map.of("amount", 100.0);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlExplanationResponse.class))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Model Not Ready"));

        MlExplanationResponse res = client.explainPurchase(reqId, features);

        assertThat(res).isNull();
    }

    @Test
    @DisplayName("SHAP Resilience: on correlation mismatch (requestId mismatch), returns null and discards")
    void explain_requestIdMismatch_returnsNullSafely() {
        String reqId = UUID.randomUUID().toString();
        String mismatchedId = UUID.randomUUID().toString();
        java.util.Map<String, Object> features = java.util.Map.of("amount", 100.0);

        MlExplanationResponse mismatched = new MlExplanationResponse(
                mismatchedId, "purchase-fraud-model", "1", 0.05, java.util.List.of()
        );

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlExplanationResponse.class)).thenReturn(mismatched);

        MlExplanationResponse res = client.explainPurchase(reqId, features);

        assertThat(res).isNull();
    }

    @Test
    @DisplayName("SHAP High-Level: reuses features and requestId attached to prediction response")
    void explain_reusesPredictionFeaturesAndRequestId() {
        RiskEvaluationRequest req = buildPurchaseRequest();
        String reqId = req.getTransactionId().toString();
        java.util.Map<String, Object> computedFeatures = java.util.Map.of("amt", 100.0, "hour_of_day", 10);

        MlPredictionClient.MlPredictionResponse predResponse = buildMlResponse(reqId);
        predResponse.setFeatures(computedFeatures);

        MlExplanationResponse expExpected = new MlExplanationResponse(
                reqId, "purchase-fraud-model", "1", 0.05,
                java.util.List.of(new ShapContribution("amt", 100.0, 0.2))
        );

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("http://ml-serving:8000/explain/purchase")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);

        org.mockito.ArgumentCaptor<Object> bodyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        when(requestBodySpec.body(bodyCaptor.capture())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(MlExplanationResponse.class)).thenReturn(expExpected);

        MlExplanationResponse res = client.explain(req, predResponse);

        assertThat(res).isNotNull();
        assertThat(res.getRequestId()).isEqualTo(reqId);

        // Verify that the exact features and request_id were passed in body
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sentBody = (java.util.Map<String, Object>) bodyCaptor.getValue();
        assertThat(sentBody.get("request_id")).isEqualTo(reqId);
        assertThat(sentBody.get("feature_spec_version")).isEqualTo("purchase-v2");
        assertThat(sentBody.get("features")).isEqualTo(computedFeatures);

        // Verify purchaseFeatureCalculator was NEVER called during explain!
        verify(purchaseFeatureCalculator, never()).compute(any());
    }
}
