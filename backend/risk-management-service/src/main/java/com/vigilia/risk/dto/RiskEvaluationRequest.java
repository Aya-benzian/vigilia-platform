package com.vigilia.risk.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class RiskEvaluationRequest {

    @NotNull(message = "transactionId is required")
    private UUID transactionId;

    @NotNull(message = "customerId is required")
    private UUID customerId;

    @NotNull(message = "transactionType is required")
    private TransactionType transactionType;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    private String currency;

    @NotNull(message = "timestamp is required")
    private Instant timestamp;

    // Customer demographics (from customer-service)
    private Integer customerAge;
    private String customerGender;
    private Double customerLatitude;
    private Double customerLongitude;
    private Integer cityPopulation;

    // Transaction specifics
    private String merchantCategory;
    private Double merchantLatitude;
    private Double merchantLongitude;

    public RiskEvaluationRequest() {}

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Integer getCustomerAge() { return customerAge; }
    public void setCustomerAge(Integer customerAge) { this.customerAge = customerAge; }

    public String getCustomerGender() { return customerGender; }
    public void setCustomerGender(String customerGender) { this.customerGender = customerGender; }

    public Double getCustomerLatitude() { return customerLatitude; }
    public void setCustomerLatitude(Double customerLatitude) { this.customerLatitude = customerLatitude; }

    public Double getCustomerLongitude() { return customerLongitude; }
    public void setCustomerLongitude(Double customerLongitude) { this.customerLongitude = customerLongitude; }

    public Integer getCityPopulation() { return cityPopulation; }
    public void setCityPopulation(Integer cityPopulation) { this.cityPopulation = cityPopulation; }

    public String getMerchantCategory() { return merchantCategory; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }

    public Double getMerchantLatitude() { return merchantLatitude; }
    public void setMerchantLatitude(Double merchantLatitude) { this.merchantLatitude = merchantLatitude; }

    public Double getMerchantLongitude() { return merchantLongitude; }
    public void setMerchantLongitude(Double merchantLongitude) { this.merchantLongitude = merchantLongitude; }
}
