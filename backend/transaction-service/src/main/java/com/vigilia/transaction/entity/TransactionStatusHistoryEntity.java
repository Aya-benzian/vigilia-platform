package com.vigilia.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only state machine audit history recording all transaction status transitions.
 * Schema: transaction | Table: transaction_status_history
 */
@Entity
@Table(name = "transaction_status_history", schema = "transaction")
public class TransactionStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "from_status", updatable = false, length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private String toStatus;

    @Column(name = "reason", updatable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", updatable = false, length = 100)
    private String actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TransactionStatusHistoryEntity() {
    }

    public TransactionStatusHistoryEntity(UUID transactionId, String fromStatus, String toStatus, String reason, String actorType, String actorId) {
        this.transactionId = transactionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorType = actorType;
        this.actorId = actorId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
