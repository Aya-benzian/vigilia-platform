package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.TransactionStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionStatusHistoryRepository extends JpaRepository<TransactionStatusHistoryEntity, UUID> {

    List<TransactionStatusHistoryEntity> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
