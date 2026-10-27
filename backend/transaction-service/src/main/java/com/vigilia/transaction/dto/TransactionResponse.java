package com.vigilia.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TransactionResponse {

    private UUID id;
    private UUID customerId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
    private RiskStatus status;
    private Double riskScore;
    private String decisionReason;
    private Instant timestamp;

    public TransactionResponse() {}

    public TransactionResponse(UUID id, UUID customerId, TransactionType type, BigDecimal amount, String currency, RiskStatus status, Double riskScore, String decisionReason, Instant timestamp) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.riskScore = riskScore;
        this.decisionReason = decisionReason;
        this.timestamp = timestamp;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public RiskStatus getStatus() { return status; }
    public void setStatus(RiskStatus status) { this.status = status; }

    public Double getRiskScore() { return riskScore; }
    public void setRiskScore(Double riskScore) { this.riskScore = riskScore; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
