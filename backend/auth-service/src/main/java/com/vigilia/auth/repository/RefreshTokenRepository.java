package com.vigilia.auth.repository;

import com.vigilia.auth.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.isRevoked = true WHERE r.familyId = :familyId")
    void revokeFamily(@Param("familyId") UUID familyId);

    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.isRevoked = true WHERE r.userId = :userId")
    void revokeAllByUserId(@Param("userId") UUID userId);
}
