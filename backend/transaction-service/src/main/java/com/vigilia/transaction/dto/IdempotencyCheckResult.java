package com.vigilia.transaction.dto;

/**
 * Result of checking an idempotency key against the store.
 */
public class IdempotencyCheckResult {

    public enum Status {
        /**
         * Key is fresh (not seen before or expired). The caller should proceed with execution.
         */
        NEW,

        /**
         * Key was already processed with identical request hash. Cached response should be returned.
         */
        CACHED,

        /**
         * Key was already used with a different request hash. Caller must reject with HTTP 409 Conflict.
         */
        CONFLICT
    }

    private final Status status;
    private final Integer responseStatus;
    private final String responseBody;
    private final String requestHash;

    private IdempotencyCheckResult(Status status, Integer responseStatus, String responseBody, String requestHash) {
        this.status = status;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.requestHash = requestHash;
    }

    public static IdempotencyCheckResult newKey(String requestHash) {
        return new IdempotencyCheckResult(Status.NEW, null, null, requestHash);
    }

    public static IdempotencyCheckResult cached(int responseStatus, String responseBody, String requestHash) {
        return new IdempotencyCheckResult(Status.CACHED, responseStatus, responseBody, requestHash);
    }

    public static IdempotencyCheckResult conflict(String requestHash) {
        return new IdempotencyCheckResult(Status.CONFLICT, null, null, requestHash);
    }

    public boolean isNew() {
        return status == Status.NEW;
    }

    public boolean isCached() {
        return status == Status.CACHED;
    }

    public boolean isConflict() {
        return status == Status.CONFLICT;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
