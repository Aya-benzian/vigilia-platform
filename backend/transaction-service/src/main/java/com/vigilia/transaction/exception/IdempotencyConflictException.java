package com.vigilia.transaction.exception;

/**
 * Exception thrown when an Idempotency-Key is reused with a different request payload (HTTP 409 Conflict).
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String key;
    private final String existingHash;
    private final String incomingHash;

    public IdempotencyConflictException(String message) {
        super(message);
        this.key = null;
        this.existingHash = null;
        this.incomingHash = null;
    }

    public IdempotencyConflictException(String key, String existingHash, String incomingHash) {
        super(String.format(
                "Conflit d'idempotence pour la clé '%s' : une requête existe déjà avec un contenu métier différent",
                key));
        this.key = key;
        this.existingHash = existingHash;
        this.incomingHash = incomingHash;
    }

    public String getKey() {
        return key;
    }

    public String getExistingHash() {
        return existingHash;
    }

    public String getIncomingHash() {
        return incomingHash;
    }
}
