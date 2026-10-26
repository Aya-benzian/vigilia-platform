package com.vigilia.transaction.exception;

/**
 * Exception thrown when an upstream internal dependency (e.g. Risk Management) is unavailable (HTTP 503).
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
