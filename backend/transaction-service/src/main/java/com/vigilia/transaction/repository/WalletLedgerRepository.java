package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.WalletLedgerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntity, UUID> {

    List<WalletLedgerEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    Page<WalletLedgerEntity> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    List<WalletLedgerEntity> findByTransactionId(UUID transactionId);
}
