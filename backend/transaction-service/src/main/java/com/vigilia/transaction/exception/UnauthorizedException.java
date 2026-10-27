package com.vigilia.transaction.exception;

/**
 * Exception thrown when authentication or identity header is missing (HTTP 401).
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
