package com.vigilia.transaction.exception;

/**
 * Exception thrown when authenticated caller is not authorized to access the requested resource (HTTP 403).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
