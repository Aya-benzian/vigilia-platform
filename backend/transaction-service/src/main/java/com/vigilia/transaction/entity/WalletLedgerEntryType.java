package com.vigilia.transaction.entity;

/**
 * Standard movement entry types recorded in the immutable wallet ledger.
 */
public enum WalletLedgerEntryType {
    /**
     * Funds credited to available balance (e.g. deposit, refund, inbound transfer).
     */
    CREDIT,

    /**
     * Direct funds debited from available balance (e.g. immediate charge).
     */
    DEBIT,

    /**
     * Funds reserved from available balance to held balance pending evaluation or settlement.
     */
    HOLD,

    /**
     * Reserved funds returned from held balance back to available balance (e.g. rejected or cancelled transaction).
     */
    RELEASE,

    /**
     * Final settlement consuming held funds (e.g. approved transaction capture).
     */
    SETTLEMENT
}
