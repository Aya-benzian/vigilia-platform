package com.vigilia.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * DTO for sandbox card deposit / wallet top-up.
 */
public class TopUpRequest {

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    private BigDecimal amount;

    private String currency = "USD";
    private String cardBrand = "VISA";
    private String cardLast4 = "4242";

    public TopUpRequest() {
    }

    public TopUpRequest(BigDecimal amount, String cardBrand, String cardLast4) {
        this.amount = amount;
        this.cardBrand = cardBrand;
        this.cardLast4 = cardLast4;
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

    public String getCardBrand() {
        return cardBrand;
    }

    public void setCardBrand(String cardBrand) {
        this.cardBrand = cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public void setCardLast4(String cardLast4) {
        this.cardLast4 = cardLast4;
    }
}

