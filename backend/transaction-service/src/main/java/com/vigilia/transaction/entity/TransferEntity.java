package com.vigilia.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Entity for TRANSFER transaction details.
 * Schema: transaction | Table: transfers
 */
@Entity
@Table(name = "transfers", schema = "transaction")
public class TransferEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "sender_wallet_id", nullable = false)
    private UUID senderWalletId;

    @Column(name = "recipient_wallet_id", nullable = false)
    private UUID recipientWalletId;

    @Column(name = "recipient_customer_id", nullable = false)
    private UUID recipientCustomerId;

    @Column(name = "memo", length = 140)
    private String memo;

    public TransferEntity() {
    }

    public TransferEntity(UUID transactionId, UUID senderWalletId, UUID recipientWalletId, UUID recipientCustomerId, String memo) {
        this.transactionId = transactionId;
        this.senderWalletId = senderWalletId;
        this.recipientWalletId = recipientWalletId;
        this.recipientCustomerId = recipientCustomerId;
        this.memo = memo;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getSenderWalletId() {
        return senderWalletId;
    }

    public void setSenderWalletId(UUID senderWalletId) {
        this.senderWalletId = senderWalletId;
    }

    public UUID getRecipientWalletId() {
        return recipientWalletId;
    }

    public void setRecipientWalletId(UUID recipientWalletId) {
        this.recipientWalletId = recipientWalletId;
    }

    public UUID getRecipientCustomerId() {
        return recipientCustomerId;
    }

    public void setRecipientCustomerId(UUID recipientCustomerId) {
        this.recipientCustomerId = recipientCustomerId;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }
}
