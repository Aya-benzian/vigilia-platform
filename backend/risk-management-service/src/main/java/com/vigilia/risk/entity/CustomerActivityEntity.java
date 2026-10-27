package com.vigilia.risk.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Rolling behavioural activity projection used to compute velocity features.
 * Schema: risk | Table: customer_activity
 * One record per transaction_id (UNIQUE).
 * Written by the risk service after a final settlement event is received.
 */
@Entity
@Table(name = "customer_activity")
public class CustomerActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "counterparty_id", length = 100)
    private String counterpartyId;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lon", precision = 9, scale = 6)
    private BigDecimal lon;

    @Column(name = "final_status", nullable = false, length = 30)
    private String finalStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public CustomerActivityEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCounterpartyId() { return counterpartyId; }
    public void setCounterpartyId(String counterpartyId) { this.counterpartyId = counterpartyId; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }

    public BigDecimal getLon() { return lon; }
    public void setLon(BigDecimal lon) { this.lon = lon; }

    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
