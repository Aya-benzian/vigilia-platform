package com.vigilia.risk.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vigilia.risk.dto.MlExplanationResponse;
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
 * HTTP client for the ML Serving prediction & explanation APIs — M2 contract (FastAPI, port 8000).
 *
 * Supported domains:
 *   - Purchase Prediction:  POST /predict/purchase — feature_spec version: purchase-v2 (29 features)
 *   - Transfer Prediction:  POST /predict/transfer — feature_spec version: transfer-v2 (20 features)
 *   - Purchase Explanation: POST /explain/purchase — feature_spec version: purchase-v2 (29 features)
 *   - Transfer Explanation: POST /explain/transfer — feature_spec version: transfer-v2 (20 features)
 *
 * Timeouts are configurable via application.properties:
 *   risk.ml.url           → base URL (default: http://ml-serving:8000)
 *   risk.ml.timeout-ms    → read timeout in ms (default: 1500)
 *
 * Resilience:
 *   - On prediction failure (timeout, 503, parse error), predict() returns null so the caller
 *     safely falls back to PENDING_REVIEW (never APPROVED).
 *   - On explanation failure (timeout, 503, network, requestId mismatch), explain() logs a WARN
 *     and returns null. Prediction validity and business decisions remain unaffected.
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
     * Computes the point-in-time features once and attaches them to the returned response
     * for seamless reuse by the explanation step.
     *
     * @param request the full risk evaluation request
     * @return the ML prediction response with computed features attached, or null on any failure
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

    /**
     * Direct purchase prediction endpoint (/predict/purchase) with explicit requestId and features.
     */
    public MlPredictionResponse predictPurchase(String requestId, Map<String, Object> features) {
        Map<String, Object> body = Map.of(
                "request_id",           requestId,
                "feature_spec_version", "purchase-v2",
                "features",             features
        );

        log.debug("[ML] POST /predict/purchase transactionId/requestId={} featureCount={}",
                requestId, features.size());

        MlPredictionResponse response = restClient.post()
                .uri(mlBaseUrl + "/predict/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MlPredictionResponse.class);

        if (response != null) {
            response.setFeatures(features);
            if (response.getRequestId() == null) {
                response.setRequestId(requestId);
            }
        }
        return response;
    }

    /**
     * Direct transfer prediction endpoint (/predict/transfer) with explicit requestId and features.
     */
    public MlPredictionResponse predictTransfer(String requestId, Map<String, Object> features) {
        Map<String, Object> body = Map.of(
                "request_id",           requestId,
                "feature_spec_version", "transfer-v2",
                "features",             features
        );

        log.debug("[ML] POST /predict/transfer transactionId/requestId={} featureCount={}",
                requestId, features.size());

        MlPredictionResponse response = restClient.post()
                .uri(mlBaseUrl + "/predict/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MlPredictionResponse.class);

        if (response != null) {
            response.setFeatures(features);
            if (response.getRequestId() == null) {
                response.setRequestId(requestId);
            }
        }
        return response;
    }

    /**
     * High-level SHAP explanation call.
     * Reuses the exact same features and requestId obtained during the prediction step.
     *
     * @param request the original risk evaluation request
     * @param prediction the successful ML prediction response containing requestId and features
     * @return the explanation response with SHAP contributions, or null on any error (logged as WARN)
     */
    public MlExplanationResponse explain(RiskEvaluationRequest request, MlPredictionResponse prediction) {
        if (request == null || prediction == null) {
            return null;
        }

        String requestId = prediction.getRequestId();
        if (requestId == null && request.getTransactionId() != null) {
            requestId = request.getTransactionId().toString();
        }

        Map<String, Object> features = prediction.getFeatures();
        if (features == null) {
            // Fallback: compute features once if they weren't attached
            String txType = request.getTransactionType() != null ? request.getTransactionType().name() : "PURCHASE";
            if ("TRANSFER".equalsIgnoreCase(txType)) {
                features = transferFeatureCalculator.compute(request);
            } else {
                features = purchaseFeatureCalculator.compute(request);
            }
        }

        String transactionType = request.getTransactionType() != null
                ? request.getTransactionType().name()
                : "PURCHASE";

        if ("PURCHASE".equalsIgnoreCase(transactionType)) {
            return explainPurchase(requestId, features);
        } else if ("TRANSFER".equalsIgnoreCase(transactionType)) {
            return explainTransfer(requestId, features);
        } else {
            log.warn("[ML-SHAP] Unknown transactionType={}; skipping SHAP explain", transactionType);
            return null;
        }
    }

    /**
     * Calls POST /explain/purchase with specification purchase-v2 (29 features).
     */
    public MlExplanationResponse explainPurchase(String requestId, Map<String, Object> features) {
        return executeExplainCall("/explain/purchase", "purchase-v2", requestId, features);
    }

    /**
     * Calls POST /explain/transfer with specification transfer-v2 (20 features).
     */
    public MlExplanationResponse explainTransfer(String requestId, Map<String, Object> features) {
        return executeExplainCall("/explain/transfer", "transfer-v2", requestId, features);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private MlPredictionResponse predictPurchase(RiskEvaluationRequest req) {
        Map<String, Object> features = purchaseFeatureCalculator.compute(req);
        String requestId = req.getTransactionId() != null
                ? req.getTransactionId().toString()
                : UUID.randomUUID().toString();
        return predictPurchase(requestId, features);
    }

    private MlPredictionResponse predictTransfer(RiskEvaluationRequest req) {
        Map<String, Object> features = transferFeatureCalculator.compute(req);
        String requestId = req.getTransactionId() != null
                ? req.getTransactionId().toString()
                : UUID.randomUUID().toString();
        return predictTransfer(requestId, features);
    }

    private MlExplanationResponse executeExplainCall(String path,
                                                    String specVersion,
                                                    String requestId,
                                                    Map<String, Object> features) {
        try {
            Map<String, Object> body = Map.of(
                    "request_id",           requestId,
                    "feature_spec_version", specVersion,
                    "features",             features
            );

            log.debug("[ML-SHAP] POST {} requestId={} featureCount={}", path, requestId, features.size());

            MlExplanationResponse response = restClient.post()
                    .uri(mlBaseUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MlExplanationResponse.class);

            if (response == null) {
                log.warn("[ML-SHAP] Null response received from {} for requestId={}", path, requestId);
                return null;
            }

            if (requestId != null && !requestId.equals(response.getRequestId())) {
                log.warn("[ML-SHAP] Request ID correlation mismatch on {}: expected={}, received={}",
                        path, requestId, response.getRequestId());
                return null;
            }

            return response;
        } catch (RestClientException ex) {
            log.warn("[ML-SHAP] HTTP/Communication failure on {} for requestId={}: {}",
                    path, requestId, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("[ML-SHAP] Unexpected failure on {} for requestId={}: {}",
                    path, requestId, ex.getMessage());
            return null;
        }
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

        /** Features computed and used for this prediction (cached for SHAP explainability). */
        @JsonIgnore
        private Map<String, Object> features;

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

        public Map<String, Object> getFeatures() { return features; }
        public void setFeatures(Map<String, Object> features) { this.features = features; }

        // Backward-compatibility aliases for M1 callers and tests
        // @JsonIgnore prevents Jackson from seeing duplicate property names
        // (the real mapping is already handled by @JsonProperty on the fields above)
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getPrediction() { return getLabel(); }
        public void setPrediction(String prediction) { setLabel(prediction); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getTransaction_id() { return getRequestId(); }
        public void setTransaction_id(String transactionId) { setRequestId(transactionId); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getModel_name() { return getModelName(); }
        public void setModel_name(String modelName) { setModelName(modelName); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getModel_version() { return getModelVersion(); }
        public void setModel_version(String modelVersion) { setModelVersion(modelVersion); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Double getFraud_probability() { return getFraudProbability(); }
        public void setFraud_probability(Double fraudProbability) { setFraudProbability(fraudProbability); }
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Integer getInference_ms() { return getInferenceMs(); }
        public void setInference_ms(Integer inferenceMs) { setInferenceMs(inferenceMs); }
    }
}
