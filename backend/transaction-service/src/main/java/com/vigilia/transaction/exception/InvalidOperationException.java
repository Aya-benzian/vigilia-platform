package com.vigilia.transaction.exception;

/**
 * Exception thrown when a wallet operation violates business invariants or state constraints.
 */
public class InvalidOperationException extends RuntimeException {

    public InvalidOperationException(String message) {
        super(message);
    }

    public InvalidOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
