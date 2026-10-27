package com.vigilia.transaction.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);

        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
                    }
                    return error.getObjectName() + ": " + error.getDefaultMessage();
                })
                .collect(Collectors.joining("; "));

        if (message == null || message.isBlank()) {
            message = "Erreur de validation des données fournies";
        }

        log.warn("Erreur de validation [correlationId={}]: {}", correlationId, message);

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "VALIDATION_ERROR",
                message,
                HttpStatus.BAD_REQUEST.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);

        log.warn("Ressource introuvable [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "RESOURCE_NOT_FOUND",
                ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : "Ressource introuvable",
                HttpStatus.NOT_FOUND.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientFundsException(
            InsufficientFundsException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Solde insuffisant [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INSUFFICIENT_FUNDS",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAmountException(
            InvalidAmountException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Montant invalide [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INVALID_AMOUNT",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOperationException(
            InvalidOperationException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Opération invalide sur le portefeuille [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INVALID_OPERATION",
                ex.getMessage(),
                HttpStatus.BAD_REQUEST.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflictException(
            IdempotencyConflictException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Conflit d'idempotence [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "IDEMPOTENCY_CONFLICT",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorizedException(
            UnauthorizedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Non autorisé [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "UNAUTHORIZED",
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorResponse> handleForbiddenException(
            ForbiddenException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Accès refusé [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "FORBIDDEN",
                ex.getMessage(),
                HttpStatus.FORBIDDEN.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceUnavailableException(
            ServiceUnavailableException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.error("Service dépendant indisponible [correlationId={}]: {}", correlationId, ex.getMessage(), ex);

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "SERVICE_UNAVAILABLE",
                ex.getMessage(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);

        log.warn("Route ou ressource introuvable [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "RESOURCE_NOT_FOUND",
                "Ressource introuvable",
                HttpStatus.NOT_FOUND.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);

        log.error("Erreur interne inattendue [correlationId={}]: {}", correlationId, ex.getMessage(), ex);

        String message = "Une erreur interne inattendue est survenue";

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                message,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = org.slf4j.MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        if (request != null) {
            correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                return correlationId;
            }
        }
        return UUID.randomUUID().toString();
    }
}
