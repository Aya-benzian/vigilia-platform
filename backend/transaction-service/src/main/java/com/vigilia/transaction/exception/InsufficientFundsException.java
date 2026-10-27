package com.vigilia.transaction.exception;

import java.math.BigDecimal;

/**
 * Exception thrown when a wallet has insufficient available or held funds for a requested operation.
 */
public class InsufficientFundsException extends RuntimeException {

    private final BigDecimal available;
    private final BigDecimal required;

    public InsufficientFundsException(String message) {
        super(message);
        this.available = null;
        this.required = null;
    }

    public InsufficientFundsException(BigDecimal available, BigDecimal required) {
        super(String.format("Solde insuffisant pour exécuter l'opération : disponible=%s, requis=%s", available, required));
        this.available = available;
        this.required = required;
    }

    public BigDecimal getAvailable() {
        return available;
    }

    public BigDecimal getRequired() {
        return required;
    }
}
