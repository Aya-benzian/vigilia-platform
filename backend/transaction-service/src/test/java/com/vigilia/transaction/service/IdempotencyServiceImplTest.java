package com.vigilia.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vigilia.transaction.dto.IdempotencyCheckResult;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.entity.IdempotencyKeyEntity;
import com.vigilia.transaction.exception.IdempotencyConflictException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private ObjectMapper objectMapper;
    private IdempotencyServiceImpl idempotencyService;

    private UUID customerId;
    private String scope;
    private String key;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        idempotencyService = new IdempotencyServiceImpl(idempotencyKeyRepository, objectMapper);

        customerId = UUID.randomUUID();
        scope = idempotencyService.buildScope(customerId);
        key = UUID.randomUUID().toString();
    }

    // -------------------------------------------------------------------------
    // 1. Première clé
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("check sur une première clé retourne le statut NEW")
    void check_firstKey_returnsNewStatus() {
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.empty());

        IdempotencyCheckResult result = idempotencyService.check(scope, key, "hash-12345");

        assertThat(result).isNotNull();
        assertThat(result.isNew()).isTrue();
        assertThat(result.isCached()).isFalse();
        assertThat(result.isConflict()).isFalse();
        assertThat(result.getResponseStatus()).isNull();
        assertThat(result.getResponseBody()).isNull();
    }

    // -------------------------------------------------------------------------
    // 2. Répétition avec le même contenu
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("check sur une répétition avec le même hash retourne CACHED avec la réponse")
    void check_replayWithSameContent_returnsCachedStatusAndResponse() {
        String hash = "hash-exact-match-1234567890abcdef1234567890abcdef1234567890abcdef123456";
        String cachedJson = "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"APPROVED\"}";
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        IdempotencyKeyEntity existing = new IdempotencyKeyEntity(scope, key, hash, 201, cachedJson, expiresAt);
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.of(existing));

        IdempotencyCheckResult result = idempotencyService.check(scope, key, hash);

        assertThat(result).isNotNull();
        assertThat(result.isCached()).isTrue();
        assertThat(result.isNew()).isFalse();
        assertThat(result.isConflict()).isFalse();
        assertThat(result.getResponseStatus()).isEqualTo(201);
        assertThat(result.getResponseBody()).isEqualTo(cachedJson);
    }

    // -------------------------------------------------------------------------
    // 3. Même clé avec un contenu différent (409)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("check sur la même clé avec un hash différent retourne CONFLICT")
    void check_sameKeyWithDifferentContent_returnsConflictStatus() {
        String originalHash = "hash-original-1234567890abcdef1234567890abcdef1234567890abcdef123456";
        String incomingHash = "hash-MODIFIED-1234567890abcdef1234567890abcdef1234567890abcdef123456";
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        IdempotencyKeyEntity existing = new IdempotencyKeyEntity(scope, key, originalHash, 201, "{}", expiresAt);
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.of(existing));

        IdempotencyCheckResult result = idempotencyService.check(scope, key, incomingHash);

        assertThat(result).isNotNull();
        assertThat(result.isConflict()).isTrue();
        assertThat(result.isCached()).isFalse();
        assertThat(result.isNew()).isFalse();
    }

    @Test
    @DisplayName("checkOrThrow sur la même clé avec hash différent lance IdempotencyConflictException (HTTP 409)")
    void checkOrThrow_sameKeyWithDifferentContent_throwsIdempotencyConflictException() {
        String originalHash = "hash-orig";
        String incomingHash = "hash-diff";
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        IdempotencyKeyEntity existing = new IdempotencyKeyEntity(scope, key, originalHash, 201, "{}", expiresAt);
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.checkOrThrow(scope, key, incomingHash))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Conflit d'idempotence")
                .satisfies(ex -> {
                    IdempotencyConflictException ice = (IdempotencyConflictException) ex;
                    assertThat(ice.getKey()).isEqualTo(key);
                    assertThat(ice.getExistingHash()).isEqualTo(originalHash);
                    assertThat(ice.getIncomingHash()).isEqualTo(incomingHash);
                });
    }

    // -------------------------------------------------------------------------
    // 4. Clés distinctes et scopes distincts
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deux clés distinctes pour le même client ne collisionnent pas")
    void check_distinctKeys_independentResults() {
        String key1 = "key-alpha";
        String key2 = "key-beta";
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key1)).thenReturn(Optional.empty());

        IdempotencyCheckResult res1 = idempotencyService.check(scope, key1, "hash1");
        assertThat(res1.isNew()).isTrue();

        verify(idempotencyKeyRepository, never()).findByScopeAndKey(scope, key2);
    }

    @Test
    @DisplayName("La même clé utilisée par deux clients différents a deux scopes différents")
    void check_distinctScopes_preventCrossCustomerCollision() {
        UUID customerA = UUID.randomUUID();
        UUID customerB = UUID.randomUUID();
        String scopeA = idempotencyService.buildScope(customerA);
        String scopeB = idempotencyService.buildScope(customerB);
        String sharedKey = "user-shared-key-123";

        assertThat(scopeA).isNotEqualTo(scopeB);
        assertThat(scopeA).isEqualTo("customer:" + customerA);
        assertThat(scopeB).isEqualTo("customer:" + customerB);
    }

    // -------------------------------------------------------------------------
    // 5. Clé expirée
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Une clé expirée (expires_at < now) est traitée comme NEW")
    void check_expiredKey_returnsNewStatus() {
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        IdempotencyKeyEntity expired = new IdempotencyKeyEntity(
                scope, key, "old-hash", 201, "{}", expiredAt);

        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.of(expired));

        IdempotencyCheckResult result = idempotencyService.check(scope, key, "old-hash");

        assertThat(result).isNotNull();
        assertThat(result.isNew()).isTrue();
        assertThat(result.isCached()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 6. Enregistrement d'une réponse avec statut HTTP et JSON
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("recordResponse persiste le hash, le statut HTTP, le JSON et l'expiration")
    void recordResponse_savesResponseWithHttpStatusAndJson() {
        String hash = "canonical-hash-64-characters-0123456789abcdef0123456789abcdef0123";
        int status = 201;
        String jsonBody = "{\"id\":\"" + UUID.randomUUID() + "\",\"amount\":100.00}";
        Duration ttl = Duration.ofHours(48);

        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any(IdempotencyKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyKeyEntity saved = idempotencyService.recordResponse(scope, key, hash, status, jsonBody, ttl);

        assertThat(saved).isNotNull();
        assertThat(saved.getScope()).isEqualTo(scope);
        assertThat(saved.getKey()).isEqualTo(key);
        assertThat(saved.getRequestHash()).isEqualTo(hash);
        assertThat(saved.getResponseStatus()).isEqualTo(201);
        assertThat(saved.getResponseBody()).isEqualTo(jsonBody);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(47)));

        ArgumentCaptor<IdempotencyKeyEntity> captor = ArgumentCaptor.forClass(IdempotencyKeyEntity.class);
        verify(idempotencyKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getResponseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("recordResponse avec DTO sérialise en JSON et persiste")
    void recordResponse_withDto_serializesAndSaves() {
        String hash = "hash-dto-test";
        UUID txnId = UUID.randomUUID();
        TransactionResponse dto = new TransactionResponse(
                txnId, customerId, TransactionType.PURCHASE, new BigDecimal("49.99"),
                "EUR", RiskStatus.APPROVED, 0.05, "Low risk", Instant.now());

        when(idempotencyKeyRepository.findByScopeAndKey(scope, key)).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any(IdempotencyKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyKeyEntity saved = idempotencyService.recordResponse(scope, key, hash, 201, dto, null);

        assertThat(saved.getResponseBody()).contains(txnId.toString());
        assertThat(saved.getResponseBody()).contains("APPROVED");

        Optional<TransactionResponse> parsed = idempotencyService.parseResponseBody(
                saved.getResponseBody(), TransactionResponse.class);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().getId()).isEqualTo(txnId);
        assertThat(parsed.get().getAmount()).isEqualByComparingTo("49.99");
    }

    // -------------------------------------------------------------------------
    // 7. Concurrence et contraintes de persistance
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("recordResponse gère la collision concurrente en rechargeant l'entité commitée")
    void recordResponse_concurrentCollision_reloadsCommittedEntity() {
        String hash = "concurrent-hash";
        when(idempotencyKeyRepository.findByScopeAndKey(scope, key))
                .thenReturn(Optional.empty()) // First lookup: not found
                .thenReturn(Optional.of(new IdempotencyKeyEntity(scope, key, hash, 201, "{\"concurrent\":true}", Instant.now().plus(Duration.ofHours(24))))); // Reload after collision

        when(idempotencyKeyRepository.save(any(IdempotencyKeyEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        IdempotencyKeyEntity resolved = idempotencyService.recordResponse(scope, key, hash, 201, "{}", null);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getResponseBody()).contains("concurrent");
    }

    // -------------------------------------------------------------------------
    // 8. Calcul stable du hash SHA-256
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("calculateRequestHash produit le même hash pour deux requêtes aux données métier identiques")
    void calculateRequestHash_deterministicAndCanonical() {
        TransactionCreateRequest req1 = new TransactionCreateRequest();
        req1.setCustomerId(customerId);
        req1.setType(TransactionType.PURCHASE);
        req1.setAmount(new BigDecimal("100.00"));
        req1.setCurrency("EUR");
        req1.setMerchantName("Amazon");
        req1.setCategory("ELECTRONICS");
        req1.setMerchantLatitude(48.8566);
        req1.setMerchantLongitude(2.3522);

        TransactionCreateRequest req2 = new TransactionCreateRequest();
        req2.setCustomerId(customerId);
        req2.setType(TransactionType.PURCHASE);
        req2.setAmount(new BigDecimal("100")); // Same value, different scale notation
        req2.setCurrency("eur "); // lowercase with trailing space
        req2.setMerchantName("Amazon");
        req2.setCategory("electronics "); // lowercase
        req2.setMerchantLatitude(48.856600);
        req2.setMerchantLongitude(2.352200);

        String hash1 = idempotencyService.calculateRequestHash(req1);
        String hash2 = idempotencyService.calculateRequestHash(req2);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex length
    }

    @Test
    @DisplayName("calculateRequestHash produit des hash différents si un champ métier varie")
    void calculateRequestHash_differentBusinessContent_producesDifferentHash() {
        TransactionCreateRequest req1 = new TransactionCreateRequest();
        req1.setCustomerId(customerId);
        req1.setType(TransactionType.PURCHASE);
        req1.setAmount(new BigDecimal("50.00"));
        req1.setCurrency("EUR");

        TransactionCreateRequest req2 = new TransactionCreateRequest();
        req2.setCustomerId(customerId);
        req2.setType(TransactionType.PURCHASE);
        req2.setAmount(new BigDecimal("50.01")); // 1 cent difference
        req2.setCurrency("EUR");

        String hash1 = idempotencyService.calculateRequestHash(req1);
        String hash2 = idempotencyService.calculateRequestHash(req2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // -------------------------------------------------------------------------
    // 9. Validation des entrées et erreurs
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("validateScopeAndKey rejette les clés nulles, vides ou > 100 caractères")
    void validateScopeAndKey_invalidInputs_throwInvalidOperationException() {
        assertThatThrownBy(() -> idempotencyService.check("", key, "hash"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("scope d'idempotence ne doit pas être vide");

        assertThatThrownBy(() -> idempotencyService.check(scope, "   ", "hash"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Idempotency-Key ne doit pas être vide");

        String longKey = "a".repeat(101);
        assertThatThrownBy(() -> idempotencyService.check(scope, longKey, "hash"))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("dépasse la taille maximale autorisée");
    }

    // -------------------------------------------------------------------------
    // 10. Purge des clés expirées
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("purgeExpiredKeys invoque la suppression des enregistrements expirés")
    void purgeExpiredKeys_callsRepository() {
        when(idempotencyKeyRepository.deleteByExpiresAtBefore(any(Instant.class))).thenReturn(5L);

        long count = idempotencyService.purgeExpiredKeys();

        assertThat(count).isEqualTo(5L);
        verify(idempotencyKeyRepository).deleteByExpiresAtBefore(any(Instant.class));
    }
}
