package com.vigilia.transaction.service;

import com.vigilia.transaction.entity.WalletLedgerEntity;
import com.vigilia.transaction.entity.WalletLedgerEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service dedicated to appending immutable records to the wallet ledger.
 */
public interface WalletLedgerService {

    /**
     * Appends an immutable entry to the wallet ledger.
     *
     * @param walletId      Target wallet ID (not null)
     * @param transactionId Associated transaction ID (nullable for standalone deposits)
     * @param entryType     Movement type (CREDIT, DEBIT, HOLD, RELEASE, SETTLEMENT)
     * @param amount        Movement amount (must be positive)
     * @param balanceAfter  Resulting available balance after this movement (must be non-negative)
     * @return The persisted immutable ledger entity
     */
    WalletLedgerEntity recordEntry(UUID walletId, UUID transactionId, WalletLedgerEntryType entryType, BigDecimal amount, BigDecimal balanceAfter);

    /**
     * Overloaded method accepting String entry type.
     */
    WalletLedgerEntity recordEntry(UUID walletId, UUID transactionId, String entryType, BigDecimal amount, BigDecimal balanceAfter);

    /**
     * Retrieves all ledger entries for a wallet ordered by createdAt DESC.
     */
    List<WalletLedgerEntity> getLedgerEntries(UUID walletId);

    /**
     * Retrieves paginated ledger entries for a wallet.
     */
    Page<WalletLedgerEntity> getLedgerEntries(UUID walletId, Pageable pageable);

    /**
     * Retrieves all ledger movements associated with a specific transaction ID.
     */
    List<WalletLedgerEntity> getEntriesByTransactionId(UUID transactionId);
}
