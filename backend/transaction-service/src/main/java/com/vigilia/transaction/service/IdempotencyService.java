package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.IdempotencyCheckResult;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.entity.IdempotencyKeyEntity;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing API request idempotency, SHA-256 canonical hashing,
 * conflict detection (HTTP 409) and response caching.
 */
public interface IdempotencyService {

    /**
     * Default TTL duration for idempotency keys (24 hours).
     */
    Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Builds standard per-customer scope for transaction idempotency keys.
     */
    String buildScope(UUID customerId);

    /**
     * Computes deterministic, canonical SHA-256 hash of TransactionCreateRequest business fields.
     */
    String calculateRequestHash(TransactionCreateRequest request);

    /**
     * Computes standard SHA-256 hash of arbitrary string payload.
     */
    String calculateHash(String payload);

    /**
     * F1 fix: Atomically claims an idempotency slot via INSERT before any financial effects.
     * Returns true if this caller owns the slot; false if another concurrent caller beat us.
     */
    boolean claimKey(String scope, String key, String requestHash);

    /**
     * Checks the state of an idempotency key.
     *
     * @param scope       Namespace/scope (e.g. "customer:<uuid>")
     * @param key         Client-provided Idempotency-Key
     * @param requestHash Computed SHA-256 hash of incoming request
     * @return IdempotencyCheckResult (NEW, CACHED, or CONFLICT)
     */
    IdempotencyCheckResult check(String scope, String key, String requestHash);

    /**
     * Checks the state of an idempotency key and throws IdempotencyConflictException
     * if a conflicting payload was previously used.
     */
    IdempotencyCheckResult checkOrThrow(String scope, String key, String requestHash);

    /**
     * Records a final HTTP response for the given idempotency key.
     */
    IdempotencyKeyEntity recordResponse(String scope, String key, String requestHash, int responseStatus, String responseBody, Duration ttl);

    /**
     * Records a final response by serializing the DTO to JSON.
     */
    IdempotencyKeyEntity recordResponse(String scope, String key, String requestHash, int responseStatus, Object responseDto, Duration ttl);

    /**
     * Deserializes a cached JSON response body into the target DTO class.
     */
    <T> Optional<T> parseResponseBody(String responseBody, Class<T> targetClass);

    /**
     * Purges expired idempotency keys from the database.
     */
    long purgeExpiredKeys();
}
