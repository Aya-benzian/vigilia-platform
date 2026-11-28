package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.IdempotencyKeyEntity;
import com.vigilia.transaction.entity.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyId> {

    Optional<IdempotencyKeyEntity> findByScopeAndKey(String scope, String key);

    boolean existsByScopeAndKey(String scope, String key);

    long deleteByExpiresAtBefore(Instant now);

    @Modifying
    @Query("DELETE FROM IdempotencyKeyEntity k WHERE k.scope = :scope AND k.key = :key AND k.expiresAt < :now")
    int deleteExpiredByScopeAndKey(@Param("scope") String scope,
                                   @Param("key") String key,
                                   @Param("now") Instant now);

    @Modifying
    @Query(value = "INSERT INTO transaction.idempotency_keys " +
                   "(scope, \"key\", request_hash, response_status, response_body, created_at, expires_at) " +
                   "VALUES (:scope, :key, :requestHash, 0, NULL, :now, :expiresAt) " +
                   "ON CONFLICT (scope, \"key\") DO NOTHING",
           nativeQuery = true)
    int insertPlaceholderOnConflictDoNothing(@Param("scope") String scope,
                                             @Param("key") String key,
                                             @Param("requestHash") String requestHash,
                                             @Param("now") Instant now,
                                             @Param("expiresAt") Instant expiresAt);
}
