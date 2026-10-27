package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    Optional<WalletEntity> findByCustomerId(UUID customerId);

    Optional<WalletEntity> findByAccountNumber(String accountNumber);

    boolean existsByCustomerId(UUID customerId);

    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.id = :id")
    Optional<WalletEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.customerId = :customerId")
    Optional<WalletEntity> findByCustomerIdForUpdate(@Param("customerId") UUID customerId);
}
