package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.entity.WalletEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service for managing customer wallets and executing concurrent-safe financial balance operations.
 */
public interface WalletService {

    /**
     * Consult a wallet by its ID.
     */
    WalletResponse getWallet(UUID walletId);

    /**
     * Consult a wallet by its owner customer ID.
     */
    WalletResponse getWalletByCustomerId(UUID customerId);

    /**
     * Internal method to retrieve the managed entity by ID.
     */
    WalletEntity getWalletEntity(UUID walletId);

    /**
     * Internal method to retrieve the managed entity by customer ID.
     */
    WalletEntity getWalletEntityByCustomerId(UUID customerId);

    /**
     * Creates a new wallet for a customer.
     */
    WalletResponse createWallet(UUID customerId, String accountNumber, String currency, BigDecimal initialBalance);

    /**
     * Reserves funds (HOLD) from available balance to held balance.
     * Uses pessimistic write lock.
     */
    WalletResponse holdFunds(UUID walletId, UUID transactionId, BigDecimal amount);

    /**
     * Reserves funds (HOLD) using customerId.
     * Uses pessimistic write lock.
     */
    WalletResponse holdFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount);

    /**
     * Releases reserved funds (RELEASE) from held balance back to available balance.
     * Uses pessimistic write lock.
     */
    WalletResponse releaseFunds(UUID walletId, UUID transactionId, BigDecimal amount);

    /**
     * Releases reserved funds (RELEASE) using customerId.
     * Uses pessimistic write lock.
     */
    WalletResponse releaseFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount);

    /**
     * Settles and finalizes a prior reservation (SETTLEMENT) by deducting from held balance.
     * Uses pessimistic write lock.
     */
    WalletResponse settleHold(UUID walletId, UUID transactionId, BigDecimal amount);

    /**
     * Settles a reservation using customerId.
     * Uses pessimistic write lock.
     */
    WalletResponse settleHoldByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount);

    /**
     * Direct debit from available balance (DEBIT) without prior reservation.
     * Uses pessimistic write lock.
     */
    WalletResponse debitFunds(UUID walletId, UUID transactionId, BigDecimal amount);

    /**
     * Direct debit using customerId.
     * Uses pessimistic write lock.
     */
    WalletResponse debitFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount);

    /**
     * Credits funds (CREDIT) to available balance.
     * Uses pessimistic write lock.
     */
    WalletResponse creditFunds(UUID walletId, UUID transactionId, BigDecimal amount);

    /**
     * Credits funds using customerId.
     * Uses pessimistic write lock.
     */
    WalletResponse creditFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount);
}
