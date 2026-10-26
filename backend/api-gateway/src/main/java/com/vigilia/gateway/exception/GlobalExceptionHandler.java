package com.vigilia.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            ServerWebExchange exchange) {

        String correlationId = resolveCorrelationId(exchange);
        log.warn("Ressource introuvable [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "RESOURCE_NOT_FOUND",
                ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : "Ressource introuvable",
                HttpStatus.NOT_FOUND.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex,
            ServerWebExchange exchange) {

        String correlationId = resolveCorrelationId(exchange);
        log.error("Erreur interne inattendue [correlationId={}]: {}", correlationId, ex.getMessage(), ex);

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Une erreur interne inattendue est survenue",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
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
