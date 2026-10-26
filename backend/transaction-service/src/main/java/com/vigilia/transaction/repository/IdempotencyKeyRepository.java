package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.IdempotencyKeyEntity;
import com.vigilia.transaction.entity.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyId> {

    Optional<IdempotencyKeyEntity> findByScopeAndKey(String scope, String key);

    boolean existsByScopeAndKey(String scope, String key);

    long deleteByExpiresAtBefore(java.time.Instant now);
}
