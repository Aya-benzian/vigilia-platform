package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.TransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<TransferEntity, UUID> {

    Optional<TransferEntity> findByTransactionId(UUID transactionId);

    List<TransferEntity> findBySenderWalletId(UUID senderWalletId);

    List<TransferEntity> findByRecipientWalletId(UUID recipientWalletId);

    List<TransferEntity> findByRecipientCustomerId(UUID recipientCustomerId);
}
