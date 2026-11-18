package com.vigilia.risk.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for the ML Serving prediction API — M2 contract (FastAPI, port 8000).
 *
 * Supported domains:
 *   - Purchase: POST /predict/purchase  — feature_spec version: purchase-v2 (29 features)
 *   - Transfer: POST /predict/transfer  — feature_spec version: transfer-v2 (20 features)
 *
 * Timeouts are configurable via application.properties:
 *   risk.ml.url           → base URL (default: http://ml-serving:8000)
 *   risk.ml.timeout-ms    → read timeout in ms (default: 1500)
 *
 * On any exception (timeout, 503, parse error) the caller handles the fallback
 * by returning PENDING_REVIEW. This client NEVER returns null on success.
 *
 * Response mapping (M2 contract):
 *   request_id           → maps to our transactionId echo
 *   label                → "FRAUD" | "LEGITIMATE"
 *   fraud_probability    → calibrated probability [0, 1]
 *   uncertainty          → model uncertainty [0, 1]
 *   model_name           → registered model name
 *   model_version        → MLflow model version
 *   feature_spec_version → "purchase-v2" | "transfer-v2"
 *   inference_ms         → server-side inference time
 */
@Component
public class MlPredictionClient {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionClient.class);

    private final RestClient restClient;
    private final PurchaseFeatureCalculator purchaseFeatureCalculator;
    private final TransferFeatureCalculator transferFeatureCalculator;

    @Value("${risk.ml.url:http://ml-serving:8000}")
    private String mlBaseUrl;

    public MlPredictionClient(RestClient.Builder restClientBuilder,
                              PurchaseFeatureCalculator purchaseFeatureCalculator,
                              TransferFeatureCalculator transferFeatureCalculator) {
        this.restClient = restClientBuilder.build();
        this.purchaseFeatureCalculator = purchaseFeatureCalculator;
        this.transferFeatureCalculator = transferFeatureCalculator;
    }

    /**
     * Calls the ML Serving endpoint appropriate to the transaction type.
     *
     * @param request the full risk evaluation request (must include computed features context)
     * @return the ML prediction response, or null on any failure (timeout, 5xx, parse error)
     */
    public MlPredictionResponse predict(RiskEvaluationRequest request) {
        String transactionType = request.getTransactionType() != null
                ? request.getTransactionType().name()
                : "PURCHASE";

        try {
            if ("PURCHASE".equalsIgnoreCase(transactionType)) {
                return predictPurchase(request);
            } else if ("TRANSFER".equalsIgnoreCase(transactionType)) {
                return predictTransfer(request);
            } else {
                log.warn("[ML] Unknown transactionType={}; skipping ML call", transactionType);
                return null;
            }
        } catch (RestClientException ex) {
            log.warn("[ML] Call failed for transactionId={}, type={}: {}",
                    request.getTransactionId(), transactionType, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.error("[ML] Unexpected error for transactionId={}: {}",
                    request.getTransactionId(), ex.getMessage(), ex);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private MlPredictionResponse predictPurchase(RiskEvaluationRequest req) {
        Map<String, Object> features = purchaseFeatureCalculator.compute(req);
        String requestId = req.getTransactionId() != null
                ? req.getTransactionId().toString()
                : UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "request_id",           requestId,
                "feature_spec_version", "purchase-v2",
                "features",             features
        );

        log.debug("[ML] POST /predict/purchase transactionId={} requestId={} featureCount={}",
                req.getTransactionId(), requestId, features.size());

        return restClient.post()
                .uri(mlBaseUrl + "/predict/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MlPredictionResponse.class);
    }

    private MlPredictionResponse predictTransfer(RiskEvaluationRequest req) {
        Map<String, Object> features = transferFeatureCalculator.compute(req);
        String requestId = req.getTransactionId() != null
                ? req.getTransactionId().toString()
                : UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "request_id",           requestId,
                "feature_spec_version", "transfer-v2",
                "features",             features
        );

        log.debug("[ML] POST /predict/transfer transactionId={} requestId={} featureCount={}",
                req.getTransactionId(), requestId, features.size());

        return restClient.post()
                .uri(mlBaseUrl + "/predict/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MlPredictionResponse.class);
    }

    // -------------------------------------------------------------------------
    // Response DTO — M2 contract (replaces M1 transaction_id/prediction fields)
    // -------------------------------------------------------------------------

    /**
     * Maps directly from the ML Serving M2 PredictionResponse.
     *
     * M2 field changes vs M1:
     *   - transaction_id  → request_id
     *   - prediction      → label
     *   + feature_spec_version (new)
     */
    public static class MlPredictionResponse {

        /** Echo of the request_id sent in the request body. */
        @JsonProperty("request_id")
        private String requestId;

        @JsonProperty("model_name")
        private String modelName;

        @JsonProperty("model_version")
        private String modelVersion;

        /** "FRAUD" | "LEGITIMATE" (was 'prediction' in M1). */
        @JsonProperty("label")
        private String label;

        @JsonProperty("fraud_probability")
        private Double fraudProbability;

        @JsonProperty("uncertainty")
        private Double uncertainty;

        @JsonProperty("feature_spec_version")
        private String featureSpecVersion;

        @JsonProperty("inference_ms")
        private Integer inferenceMs;

        public MlPredictionResponse() {}

        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }

        public String getModelVersion() { return modelVersion; }
        public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

        /** Returns the prediction label ("FRAUD" | "LEGITIMATE"). Was getPrediction() in M1. */
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Double getFraudProbability() { return fraudProbability; }
        public void setFraudProbability(Double fraudProbability) { this.fraudProbability = fraudProbability; }

        public Double getUncertainty() { return uncertainty; }
        public void setUncertainty(Double uncertainty) { this.uncertainty = uncertainty; }

        public String getFeatureSpecVersion() { return featureSpecVersion; }
        public void setFeatureSpecVersion(String featureSpecVersion) { this.featureSpecVersion = featureSpecVersion; }

        public Integer getInferenceMs() { return inferenceMs; }
        public void setInferenceMs(Integer inferenceMs) { this.inferenceMs = inferenceMs; }

        // Backward-compatibility aliases for M1 callers and tests
        public String getPrediction() { return getLabel(); }
        public void setPrediction(String prediction) { setLabel(prediction); }
        public String getTransaction_id() { return getRequestId(); }
        public void setTransaction_id(String transactionId) { setRequestId(transactionId); }
        public String getModel_name() { return getModelName(); }
        public void setModel_name(String modelName) { setModelName(modelName); }
        public String getModel_version() { return getModelVersion(); }
        public void setModel_version(String modelVersion) { setModelVersion(modelVersion); }
        public Double getFraud_probability() { return getFraudProbability(); }
        public void setFraud_probability(Double fraudProbability) { setFraudProbability(fraudProbability); }
        public Integer getInference_ms() { return getInferenceMs(); }
        public void setInference_ms(Integer inferenceMs) { setInferenceMs(inferenceMs); }
    }
}
