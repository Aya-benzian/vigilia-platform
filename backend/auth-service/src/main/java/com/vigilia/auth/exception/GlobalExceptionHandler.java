package com.vigilia.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
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

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleUserAlreadyExistsException(
            UserAlreadyExistsException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Utilisateur déjà existant [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "USER_ALREADY_EXISTS",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler({AuthenticationFailedException.class, BadCredentialsException.class})
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailedException(
            Exception ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Échec d'authentification [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INVALID_CREDENTIALS",
                "Identifiants invalides",
                HttpStatus.UNAUTHORIZED.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccountLockedException(
            AccountLockedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Compte bloqué [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "ACCOUNT_LOCKED",
                ex.getMessage(),
                HttpStatus.FORBIDDEN.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidTokenException(
            InvalidTokenException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Jeton invalide [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "TOKEN_INVALID",
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleTokenExpiredException(
            TokenExpiredException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Jeton expiré [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "TOKEN_EXPIRED",
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request) {

        String correlationId = resolveCorrelationId(request);
        log.warn("Accès refusé [correlationId={}]: {}", correlationId, ex.getMessage());

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "FORBIDDEN",
                "Accès interdit pour ce rôle ou cette ressource",
                HttpStatus.FORBIDDEN.value(),
                correlationId
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
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

        ApiErrorResponse errorResponse = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Une erreur interne inattendue est survenue",
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
