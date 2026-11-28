package com.vigilia.transaction.exception;

/**
 * Thrown when the Risk Management Service is unavailable (timeout, connection error, circuit open,
 * or returns an invalid/null response). This must NEVER cause auto-approval or auto-review:
 * the caller must leave the transaction in PENDING and return HTTP 202.
 */
public class RiskUnavailableException extends RuntimeException {

    public RiskUnavailableException(String message) {
        super(message);
    }

    public RiskUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

