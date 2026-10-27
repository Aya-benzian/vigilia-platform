package com.vigilia.transaction.service;

import com.vigilia.transaction.entity.WalletLedgerEntity;
import com.vigilia.transaction.entity.WalletLedgerEntryType;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.repository.WalletLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of WalletLedgerService guaranteeing immutable append-only recordings.
 */
@Service
@Transactional(readOnly = true)
public class WalletLedgerServiceImpl implements WalletLedgerService {

    private static final Logger log = LoggerFactory.getLogger(WalletLedgerServiceImpl.class);

    private final WalletLedgerRepository walletLedgerRepository;

    public WalletLedgerServiceImpl(WalletLedgerRepository walletLedgerRepository) {
        this.walletLedgerRepository = walletLedgerRepository;
    }

    @Override
    @Transactional
    public WalletLedgerEntity recordEntry(UUID walletId, UUID transactionId, WalletLedgerEntryType entryType, BigDecimal amount, BigDecimal balanceAfter) {
        Objects.requireNonNull(entryType, "entryType ne doit pas être null");
        return recordEntry(walletId, transactionId, entryType.name(), amount, balanceAfter);
    }

    @Override
    @Transactional
    public WalletLedgerEntity recordEntry(UUID walletId, UUID transactionId, String entryType, BigDecimal amount, BigDecimal balanceAfter) {
        if (walletId == null) {
            throw new InvalidOperationException("walletId ne doit pas être null pour une écriture dans le ledger");
        }
        if (entryType == null || entryType.isBlank()) {
            throw new InvalidOperationException("entryType ne doit pas être vide pour une écriture dans le ledger");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Le montant d'écriture dans le ledger doit être strictement positif");
        }
        if (balanceAfter == null || balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidOperationException("Le solde résultant balanceAfter ne peut pas être négatif : " + balanceAfter);
        }

        WalletLedgerEntity entry = new WalletLedgerEntity(walletId, transactionId, entryType.trim().toUpperCase(), amount, balanceAfter);
        WalletLedgerEntity saved = walletLedgerRepository.save(entry);
        log.info("Écriture ledger enregistrée [walletId={}, entryType={}, amount={}, balanceAfter={}, transactionId={}]",
                walletId, entryType, amount, balanceAfter, transactionId);
        return saved;
    }

    @Override
    public List<WalletLedgerEntity> getLedgerEntries(UUID walletId) {
        if (walletId == null) {
            throw new InvalidOperationException("walletId ne doit pas être null");
        }
        return walletLedgerRepository.findByWalletIdOrderByCreatedAtDesc(walletId);
    }

    @Override
    public Page<WalletLedgerEntity> getLedgerEntries(UUID walletId, Pageable pageable) {
        if (walletId == null) {
            throw new InvalidOperationException("walletId ne doit pas être null");
        }
        return walletLedgerRepository.findByWalletIdOrderByCreatedAtDesc(walletId, pageable);
    }

    @Override
    public List<WalletLedgerEntity> getEntriesByTransactionId(UUID transactionId) {
        if (transactionId == null) {
            throw new InvalidOperationException("transactionId ne doit pas être null");
        }
        return walletLedgerRepository.findByTransactionId(transactionId);
    }
}
