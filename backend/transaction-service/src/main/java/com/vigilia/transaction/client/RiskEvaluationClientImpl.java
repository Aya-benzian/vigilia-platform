package com.vigilia.transaction.client;

import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.exception.RiskUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Implementation of RiskEvaluationClient calling Risk Management Service over HTTP.
 *
 * <p>Step 1 fix: on timeout / connection error / circuit-open / null response, throws
 * {@link RiskUnavailableException}. Never returns a synthetic RiskEvaluationResponse.
 * Never auto-approves, never auto-routes to PENDING_REVIEW: the caller must handle the
 * exception by leaving the transaction PENDING and returning HTTP 202.
 */
@Component
public class RiskEvaluationClientImpl implements RiskEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationClientImpl.class);

    private final RestClient restClient;
    private final String riskServiceUrl;

    public RiskEvaluationClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${vigilia.risk-service.url:http://localhost:8084}") String riskServiceUrl) {
        this.restClient = restClientBuilder.baseUrl(riskServiceUrl).build();
        this.riskServiceUrl = riskServiceUrl;
    }

    @Override
    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        log.info("Risk evaluation call [txnId={}, customerId={}, amount={}] -> {}",
                request.getTransactionId(), request.getCustomerId(), request.getAmount(), riskServiceUrl);

        RiskEvaluationResponse response;
        try {
            response = restClient.post()
                    .uri("/api/v1/risk/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskEvaluationResponse.class);
        } catch (Exception ex) {
            log.error("Risk Management Service unreachable ({}) for txnId={}: {}",
                    riskServiceUrl, request.getTransactionId(), ex.getMessage());
            throw new RiskUnavailableException(
                    "Risk Management Service unavailable: " + ex.getMessage(), ex);
        }

        if (response == null || response.getDecision() == null) {
            log.warn("Null/invalid risk response for txnId={}", request.getTransactionId());
            throw new RiskUnavailableException(
                    "Risk Management Service returned null or invalid response for txnId=" + request.getTransactionId());
        }

        log.info("Risk decision received [txnId={}, decision={}, score={}]",
                request.getTransactionId(), response.getDecision(), response.getFraudProbability());
        return response;
    }
}
