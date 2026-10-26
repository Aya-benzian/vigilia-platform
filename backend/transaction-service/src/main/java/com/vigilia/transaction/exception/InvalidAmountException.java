package com.vigilia.transaction.exception;

/**
 * Exception thrown when a monetary amount is null, zero, negative, or has an invalid scale.
 */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
