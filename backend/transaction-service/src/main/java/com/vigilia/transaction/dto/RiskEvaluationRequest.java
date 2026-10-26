package com.vigilia.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload sent to Risk Management Service for synchronous transaction evaluation.
 */
public class RiskEvaluationRequest {

    private UUID transactionId;
    private UUID customerId;
    private TransactionType transactionType;
    private BigDecimal amount;
    private String currency;
    private Instant timestamp;

    // Feature fields
    private String merchantCategory;
    private Double merchantLatitude;
    private Double merchantLongitude;

    public RiskEvaluationRequest() {
    }

    public RiskEvaluationRequest(UUID transactionId, UUID customerId, TransactionType transactionType, BigDecimal amount, String currency, Instant timestamp) {
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.currency = currency;
        this.timestamp = timestamp;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public void setMerchantCategory(String merchantCategory) {
        this.merchantCategory = merchantCategory;
    }

    public Double getMerchantLatitude() {
        return merchantLatitude;
    }

    public void setMerchantLatitude(Double merchantLatitude) {
        this.merchantLatitude = merchantLatitude;
    }

    public Double getMerchantLongitude() {
        return merchantLongitude;
    }

    public void setMerchantLongitude(Double merchantLongitude) {
        this.merchantLongitude = merchantLongitude;
    }
}
