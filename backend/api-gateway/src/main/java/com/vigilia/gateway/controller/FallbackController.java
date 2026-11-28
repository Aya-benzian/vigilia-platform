package com.vigilia.gateway.controller;

import com.vigilia.gateway.exception.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Reactive Fallback Controller handling Circuit Breaker fallbacks for downstream services.
 * Returns standardized 503 SERVICE_UNAVAILABLE error responses without exposing fake data
 * or masking failures as successes.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @RequestMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiErrorResponse>> authFallback(ServerWebExchange exchange) {
        return buildFallbackResponse(exchange, "auth-service",
                "Le service d'authentification est temporairement indisponible. Veuillez réessayer ultérieurement.");
    }

    @RequestMapping(value = "/customer", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiErrorResponse>> customerFallback(ServerWebExchange exchange) {
        return buildFallbackResponse(exchange, "customer-service",
                "Le service client est temporairement indisponible. Veuillez réessayer ultérieurement.");
    }

    @RequestMapping(value = "/transaction", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiErrorResponse>> transactionFallback(ServerWebExchange exchange) {
        return buildFallbackResponse(exchange, "transaction-service",
                "Le service de transactions est temporairement indisponible. Aucune transaction n'a été exécutée.");
    }

    @RequestMapping(value = "/risk", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiErrorResponse>> riskFallback(ServerWebExchange exchange) {
        return buildFallbackResponse(exchange, "risk-service",
                "Le service d'évaluation du risque est temporairement indisponible. Veuillez réessayer ultérieurement.");
    }

    @RequestMapping(value = "/notification", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiErrorResponse>> notificationFallback(ServerWebExchange exchange) {
        return buildFallbackResponse(exchange, "notification-service",
                "Le service de notifications est temporairement indisponible. Veuillez réessayer ultérieurement.");
    }

    private Mono<ResponseEntity<ApiErrorResponse>> buildFallbackResponse(
            ServerWebExchange exchange, String serviceName, String message) {

        String correlationId = resolveCorrelationId(exchange);
        Throwable exception = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);

        log.warn("Circuit Breaker fallback déclenché pour [{}] [correlationId={}]: {}",
                serviceName, correlationId, exception != null ? exception.getMessage() : "Circuit ouvert ou délai dépassé");

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "SERVICE_UNAVAILABLE",
                message,
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                correlationId,
                Instant.now().toString()
        );

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(CORRELATION_ID_HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorResponse));
    }

    private String resolveCorrelationId(ServerWebExchange exchange) {
        if (exchange != null) {
            String corr = (String) exchange.getAttributes().get(CORRELATION_ID_HEADER);
            if (corr != null && !corr.isBlank()) {
                return corr;
            }
            corr = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
            if (corr != null && !corr.isBlank()) {
                return corr;
            }
        }
        return UUID.randomUUID().toString();
    }
}
