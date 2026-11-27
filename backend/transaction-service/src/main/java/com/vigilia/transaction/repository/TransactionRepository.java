package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.TransactionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID>, JpaSpecificationExecutor<TransactionEntity> {

    Optional<TransactionEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<TransactionEntity> findByIdAndCustomerId(UUID id, UUID customerId);

    List<TransactionEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Page<TransactionEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    List<TransactionEntity> findByStatus(String status);

    boolean existsByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    // Count by type
    long countByTransactionType(String transactionType);

    // Count approved per type
    @Query("SELECT COUNT(t) FROM TransactionEntity t WHERE t.transactionType = :type AND t.status = 'APPROVED'")
    long countApprovedByType(@org.springframework.data.repository.query.Param("type") String type);

    // Sum amount by type
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t WHERE t.transactionType = :type")
    BigDecimal sumAmountByType(@org.springframework.data.repository.query.Param("type") String type);

    // Average amount by type
    @Query("SELECT COALESCE(AVG(t.amount), 0) FROM TransactionEntity t WHERE t.transactionType = :type")
    BigDecimal avgAmountByType(@org.springframework.data.repository.query.Param("type") String type);

    // Pessimistic write lock — used to guard status transitions (B1 fix)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionEntity t WHERE t.id = :id")
    Optional<TransactionEntity> findByIdForUpdate(@Param("id") UUID id);
}
