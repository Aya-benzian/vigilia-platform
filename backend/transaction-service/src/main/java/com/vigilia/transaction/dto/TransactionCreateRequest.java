package com.vigilia.transaction.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public class TransactionCreateRequest {

    @NotNull(message = "customerId is required")
    private UUID customerId;

    @NotNull(message = "type is required")
    private TransactionType type;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code (e.g. EUR, USD)")
    private String currency;

    // Feature fields for PURCHASE
    private String category;
    private String merchantName;

    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private Double merchantLatitude;

    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private Double merchantLongitude;

    // Feature fields for TRANSFER
    private String recipientIban;
    private String transferReference;

    public TransactionCreateRequest() {}

    public TransactionCreateRequest(UUID customerId, TransactionType type, BigDecimal amount, String currency) {
        this.customerId = customerId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public Double getMerchantLatitude() { return merchantLatitude; }
    public void setMerchantLatitude(Double merchantLatitude) { this.merchantLatitude = merchantLatitude; }

    public Double getMerchantLongitude() { return merchantLongitude; }
    public void setMerchantLongitude(Double merchantLongitude) { this.merchantLongitude = merchantLongitude; }

    public String getRecipientIban() { return recipientIban; }
    public void setRecipientIban(String recipientIban) { this.recipientIban = recipientIban; }

    public String getTransferReference() { return transferReference; }
    public void setTransferReference(String transferReference) { this.transferReference = transferReference; }
}
