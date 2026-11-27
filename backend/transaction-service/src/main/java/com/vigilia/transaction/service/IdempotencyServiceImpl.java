package com.vigilia.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.dto.IdempotencyCheckResult;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.entity.IdempotencyKeyEntity;
import com.vigilia.transaction.exception.IdempotencyConflictException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Robust implementation of IdempotencyService managing request deduplication,
 * canonical SHA-256 payload hashing, conflict detection (HTTP 409), and caching.
 */
@Service
@Transactional(readOnly = true)
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);
    private static final int MAX_KEY_LENGTH = 100;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyServiceImpl(IdempotencyKeyRepository idempotencyKeyRepository, ObjectMapper objectMapper) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String buildScope(UUID customerId) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null pour construire le scope d'idempotence");
        }
        return "customer:" + customerId;
    }

    @Override
    public String calculateRequestHash(TransactionCreateRequest request) {
        if (request == null) {
            throw new InvalidOperationException("TransactionCreateRequest ne doit pas être null pour le calcul de hash");
        }

        String customerIdStr = request.getCustomerId() != null ? request.getCustomerId().toString() : "";
        String typeStr = request.getType() != null ? request.getType().name() : "";
        String amountStr = request.getAmount() != null
                ? request.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "";
        String currencyStr = request.getCurrency() != null ? request.getCurrency().trim().toUpperCase() : "";

        // Purchase feature fields
        String categoryStr = request.getCategory() != null ? request.getCategory().trim().toUpperCase() : "";
        String merchantNameStr = request.getMerchantName() != null ? request.getMerchantName().trim() : "";
        String latStr = request.getMerchantLatitude() != null
                ? String.format(Locale.ROOT, "%.6f", request.getMerchantLatitude())
                : "";
        String lonStr = request.getMerchantLongitude() != null
                ? String.format(Locale.ROOT, "%.6f", request.getMerchantLongitude())
                : "";

        // Transfer feature fields
        String recipientIbanStr = request.getRecipientIban() != null
                ? request.getRecipientIban().replaceAll("\\s+", "").toUpperCase()
                : "";
        String transferRefStr = request.getTransferReference() != null
                ? request.getTransferReference().trim()
                : "";

        String canonical = String.join("|",
                customerIdStr,
                typeStr,
                amountStr,
                currencyStr,
                categoryStr,
                merchantNameStr,
                latStr,
                lonStr,
                recipientIbanStr,
                transferRefStr
        );

        return calculateHash(canonical);
    }

    @Override
    public String calculateHash(String payload) {
        if (payload == null) {
            payload = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme SHA-256 non disponible dans la JVM", e);
        }
    }

    /**
     * F1 fix: Atomically claims an idempotency slot by INSERTing a placeholder row
     * (response_status = 0) BEFORE any financial effects are applied.
     *
     * <p>Returns {@code true} if this caller owns the slot (the INSERT won the race).
     * Returns {@code false} if another concurrent request already holds the key — the
     * caller should read the existing entry and return its result instead of proceeding
     * with the financial flow.
     *
     * <p>Using the database PRIMARY KEY (scope, key) as the arbiter means only ONE
     * winner can proceed; all losers see a DataIntegrityViolationException and back off.
     */
    @Override
    @Transactional
    public boolean claimKey(String scope, String key, String requestHash) {
        validateScopeAndKey(scope, key);
        Objects.requireNonNull(requestHash, "requestHash ne doit pas être null");

        Duration effectiveTtl = DEFAULT_TTL;
        Instant expiresAt = Instant.now().plus(effectiveTtl);

        // Placeholder entity: response_status=0 signals "in-flight" to any reader
        IdempotencyKeyEntity placeholder = new IdempotencyKeyEntity(
                scope, key, requestHash, 0, null, expiresAt);
        try {
            idempotencyKeyRepository.save(placeholder);
            log.info("Slot d'idempotence revendiqué [scope={}, key={}]", scope, key);
            return true; // we won the race
        } catch (DataIntegrityViolationException e) {
            log.warn("Slot d'idempotence déjà revendiqué par une autre requête concurrente [scope={}, key={}]", scope, key);
            return false; // another request beat us
        }
    }

    @Override
    public IdempotencyCheckResult check(String scope, String key, String requestHash) {
        validateScopeAndKey(scope, key);
        Objects.requireNonNull(requestHash, "requestHash ne doit pas être null");

        Optional<IdempotencyKeyEntity> existingOpt = idempotencyKeyRepository.findByScopeAndKey(scope, key);

        if (existingOpt.isEmpty()) {
            return IdempotencyCheckResult.newKey(requestHash);
        }

        IdempotencyKeyEntity existing = existingOpt.get();
        Instant now = Instant.now();

        // Expired key handling: treat as new
        if (existing.getExpiresAt().isBefore(now)) {
            log.info("Clé d'idempotence expirée détectée [scope={}, key={}, expiredAt={}] : réinitialisation",
                    scope, key, existing.getExpiresAt());
            return IdempotencyCheckResult.newKey(requestHash);
        }

        // Active key: check hash consistency
        if (existing.getRequestHash().equals(requestHash)) {
            log.info("Répétition idempotente valide détectée [scope={}, key={}, status={}]",
                    scope, key, existing.getResponseStatus());
            return IdempotencyCheckResult.cached(existing.getResponseStatus(), existing.getResponseBody(), requestHash);
        } else {
            log.warn("Conflit d'idempotence détecté [scope={}, key={}] : hash existant={}, nouveau hash={}",
                    scope, key, existing.getRequestHash(), requestHash);
            return IdempotencyCheckResult.conflict(requestHash);
        }
    }

    @Override
    public IdempotencyCheckResult checkOrThrow(String scope, String key, String requestHash) {
        IdempotencyCheckResult result = check(scope, key, requestHash);
        if (result.isConflict()) {
            Optional<IdempotencyKeyEntity> existing = idempotencyKeyRepository.findByScopeAndKey(scope, key);
            String existingHash = existing.map(IdempotencyKeyEntity::getRequestHash).orElse("unknown");
            throw new IdempotencyConflictException(key, existingHash, requestHash);
        }
        return result;
    }

    @Override
    @Transactional
    public IdempotencyKeyEntity recordResponse(String scope, String key, String requestHash, int responseStatus, String responseBody, Duration ttl) {
        validateScopeAndKey(scope, key);
        Objects.requireNonNull(requestHash, "requestHash ne doit pas être null");

        Duration effectiveTtl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : DEFAULT_TTL;
        Instant expiresAt = Instant.now().plus(effectiveTtl);

        Optional<IdempotencyKeyEntity> existingOpt = idempotencyKeyRepository.findByScopeAndKey(scope, key);

        if (existingOpt.isPresent()) {
            IdempotencyKeyEntity existing = existingOpt.get();
            existing.setRequestHash(requestHash);
            existing.setResponseStatus(responseStatus);
            existing.setResponseBody(responseBody);
            existing.setExpiresAt(expiresAt);
            log.info("Mise à jour de la clé d'idempotence [scope={}, key={}, status={}, expiresAt={}]",
                    scope, key, responseStatus, expiresAt);
            return idempotencyKeyRepository.save(existing);
        }

        IdempotencyKeyEntity newEntity = new IdempotencyKeyEntity(
                scope, key, requestHash, responseStatus, responseBody, expiresAt);

        try {
            IdempotencyKeyEntity saved = idempotencyKeyRepository.save(newEntity);
            log.info("Nouvelle clé d'idempotence enregistrée [scope={}, key={}, status={}, expiresAt={}]",
                    scope, key, responseStatus, expiresAt);
            return saved;
        } catch (DataIntegrityViolationException e) {
            log.warn("Collision concurrente lors de la persistance de la clé d'idempotence [scope={}, key={}] : rechargement",
                    scope, key);
            // Concurrent insert race condition: reload committed record
            return idempotencyKeyRepository.findByScopeAndKey(scope, key)
                    .orElseThrow(() -> e);
        }
    }

    @Override
    @Transactional
    public IdempotencyKeyEntity recordResponse(String scope, String key, String requestHash, int responseStatus, Object responseDto, Duration ttl) {
        String jsonBody = null;
        if (responseDto != null) {
            try {
                jsonBody = objectMapper.writeValueAsString(responseDto);
            } catch (JsonProcessingException e) {
                log.error("Échec de sérialisation JSON du DTO pour idempotence [scope={}, key={}]: {}",
                        scope, key, e.getMessage());
                throw new InvalidOperationException("Impossible de sérialiser la réponse pour le cache d'idempotence", e);
            }
        }
        return recordResponse(scope, key, requestHash, responseStatus, jsonBody, ttl);
    }

    @Override
    public <T> Optional<T> parseResponseBody(String responseBody, Class<T> targetClass) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(responseBody, targetClass));
        } catch (JsonProcessingException e) {
            log.error("Échec de désérialisation du corps de réponse idempotente: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public long purgeExpiredKeys() {
        Instant now = Instant.now();
        long deletedCount = idempotencyKeyRepository.deleteByExpiresAtBefore(now);
        log.info("Purge des clés d'idempotence expirées exécutée : {} clés supprimées", deletedCount);
        return deletedCount;
    }

    private void validateScopeAndKey(String scope, String key) {
        if (scope == null || scope.isBlank()) {
            throw new InvalidOperationException("scope d'idempotence ne doit pas être vide");
        }
        if (key == null || key.isBlank()) {
            throw new InvalidOperationException("Idempotency-Key ne doit pas être vide");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidOperationException(
                    "Idempotency-Key dépasse la taille maximale autorisée de " + MAX_KEY_LENGTH + " caractères");
        }
    }
}
