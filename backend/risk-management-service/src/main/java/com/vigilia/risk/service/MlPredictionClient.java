package com.vigilia.risk.service;

import com.vigilia.risk.dto.RiskEvaluationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for the ML Serving prediction API (FastAPI, port 8000).
 * Contract: contracts/ml/prediction-contract-v1.yaml
 *
 * Timeouts are configurable via application.properties:
 *   risk.ml.url           → base URL (default: http://ml-serving:8000)
 *   risk.ml.timeout-ms    → read timeout in ms (default: 1500)
 *
 * On any exception (timeout, 503, parse error) the caller handles the fallback
 * by returning PENDING_REVIEW. This client NEVER returns null on success.
 */
@Component
public class MlPredictionClient {

    private static final Logger log = LoggerFactory.getLogger(MlPredictionClient.class);

    private final RestClient restClient;

    @Value("${risk.ml.url:http://ml-serving:8000}")
    private String mlBaseUrl;

    public MlPredictionClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * Calls the ML Serving endpoint appropriate to the transaction type.
     *
     * @param request the full risk evaluation request
     * @return the raw prediction response map, or null on any failure
     */
    public MlPredictionResponse predict(RiskEvaluationRequest request) {
        String transactionType = request.getTransactionType() != null
                ? request.getTransactionType().name()
                : "PURCHASE";

        // TRANSFER model is not officially available yet.
        // Do NOT call ML serving or send dummy UNKNOWN values.
        if (!"PURCHASE".equalsIgnoreCase(transactionType)) {
            log.info("[ML] No ML model officially available for transactionType={}; skipping ML call", transactionType);
            return null;
        }

        try {
            return predictPurchase(request);
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
        Map<String, Object> body = buildPurchaseBody(req);
        log.debug("[ML] POST /predict/purchase transactionId={}", req.getTransactionId());

        return restClient.post()
                .uri(mlBaseUrl + "/predict/purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MlPredictionResponse.class);
    }

    /**
     * Builds the PurchaseInferenceRequest per the ML contract (Sparkov dataset schema).
     * Missing optional fields fall back to cold-start sentinel values.
     */
    private Map<String, Object> buildPurchaseBody(RiskEvaluationRequest req) {
        Map<String, Object> body = new HashMap<>();
        body.put("transaction_id", req.getTransactionId() != null ? req.getTransactionId().toString() : null);
        body.put("transaction_type", "PURCHASE");
        body.put("trans_date_trans_time", req.getTimestamp() != null ? req.getTimestamp().toString() : null);
        body.put("amt", req.getAmount());
        body.put("category", req.getMerchantCategory() != null ? req.getMerchantCategory() : "misc_net");
        body.put("gender", req.getCustomerGender() != null ? req.getCustomerGender() : "M");
        // dob is derived from customerAge; if unavailable use a cold-start default (age 35)
        body.put("dob", deriveDob(req.getCustomerAge()));
        body.put("lat", req.getCustomerLatitude() != null ? req.getCustomerLatitude() : 0.0);
        body.put("long", req.getCustomerLongitude() != null ? req.getCustomerLongitude() : 0.0);
        body.put("city_pop", req.getCityPopulation() != null ? req.getCityPopulation() : 100000);
        body.put("merch_lat", req.getMerchantLatitude() != null ? req.getMerchantLatitude() : 0.0);
        body.put("merch_long", req.getMerchantLongitude() != null ? req.getMerchantLongitude() : 0.0);
        return body;
    }

    /**
     * Converts customerAge to a date-of-birth string for the ML contract.
     * Falls back to age 35 if null.
     */
    private String deriveDob(Integer customerAge) {
        int age = (customerAge != null && customerAge > 0) ? customerAge : 35;
        return LocalDate.now().minusYears(age).toString();
    }

    // -------------------------------------------------------------------------
    // Inner response DTO (maps directly from ML contract PredictionResponse)
    // -------------------------------------------------------------------------

    public static class MlPredictionResponse {
        private String transaction_id;
        private String model_name;
        private String model_version;
        private String prediction;       // "FRAUD" | "LEGITIMATE"
        private Double fraud_probability;
        private Double uncertainty;
        private Integer inference_ms;

        public MlPredictionResponse() {}

        public String getTransaction_id() { return transaction_id; }
        public void setTransaction_id(String transaction_id) { this.transaction_id = transaction_id; }

        public String getModel_name() { return model_name; }
        public void setModel_name(String model_name) { this.model_name = model_name; }

        public String getModel_version() { return model_version; }
        public void setModel_version(String model_version) { this.model_version = model_version; }

        public String getPrediction() { return prediction; }
        public void setPrediction(String prediction) { this.prediction = prediction; }

        public Double getFraud_probability() { return fraud_probability; }
        public void setFraud_probability(Double fraud_probability) { this.fraud_probability = fraud_probability; }

        public Double getUncertainty() { return uncertainty; }
        public void setUncertainty(Double uncertainty) { this.uncertainty = uncertainty; }

        public Integer getInference_ms() { return inference_ms; }
        public void setInference_ms(Integer inference_ms) { this.inference_ms = inference_ms; }
    }
}
