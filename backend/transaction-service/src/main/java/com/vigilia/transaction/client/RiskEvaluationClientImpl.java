package com.vigilia.transaction.client;

import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

/**
 * Implementation of RiskEvaluationClient calling Risk Management Service over HTTP
 * with strict failure guards (never auto-approve on failure or timeout).
 */
@Component
public class RiskEvaluationClientImpl implements RiskEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationClientImpl.class);

    private final RestClient restClient;
    private final String riskServiceUrl;
    private final boolean fallbackToPendingReview;

    public RiskEvaluationClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${vigilia.risk-service.url:http://localhost:8084}") String riskServiceUrl,
            @Value("${vigilia.risk-service.fallback-to-review:true}") boolean fallbackToPendingReview) {
        this.restClient = restClientBuilder.baseUrl(riskServiceUrl).build();
        this.riskServiceUrl = riskServiceUrl;
        this.fallbackToPendingReview = fallbackToPendingReview;
    }

    @Override
    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        log.info("Appel d'évaluation de risque [txnId={}, customerId={}, amount={}] vers {}",
                request.getTransactionId(), request.getCustomerId(), request.getAmount(), riskServiceUrl);

        try {
            RiskEvaluationResponse response = restClient.post()
                    .uri("/api/v1/risk/evaluate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskEvaluationResponse.class);

            if (response == null || response.getDecision() == null) {
                log.warn("Réponse d'évaluation de risque vide ou invalide pour txnId={}", request.getTransactionId());
                return handleUnavailableFallback(request, "INVALID_RISK_RESPONSE");
            }

            log.info("Décision de risque reçue [txnId={}, decision={}, score={}]",
                    request.getTransactionId(), response.getDecision(), response.getFraudProbability());
            return response;

        } catch (Exception ex) {
            log.error("Échec de communication avec Risk Management Service ({}) pour txnId={}: {}",
                    riskServiceUrl, request.getTransactionId(), ex.getMessage());
            return handleUnavailableFallback(request, "RISK_SERVICE_UNAVAILABLE_FALLBACK: " + ex.getMessage());
        }
    }

    private RiskEvaluationResponse handleUnavailableFallback(RiskEvaluationRequest request, String reason) {
        // Critical Rule: A failure or timeout must NEVER result in automatic approval!
        if (fallbackToPendingReview) {
            log.warn("Application du comportement de repli sécurisé PENDING_REVIEW pour txnId={}", request.getTransactionId());
            return new RiskEvaluationResponse(
                    UUID.randomUUID(),
                    request.getTransactionId(),
                    RiskStatus.PENDING_REVIEW,
                    1.0,
                    reason,
                    Instant.now()
            );
        } else {
            throw new ServiceUnavailableException("Risk Management Service est indisponible : " + reason);
        }
    }
}
