package com.vigilia.auth.repository;

import com.vigilia.auth.entity.LoginAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttemptEntity, UUID> {

    long countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(String email, Instant after);

    long countByIpAddressAndWasSuccessfulFalseAndAttemptedAtAfter(String ipAddress, Instant after);
}
