package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

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
}
