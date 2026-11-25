package com.vigilia.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request payload for linking an external bank debit/credit card as a funding source.
 */
public class LinkFundingCardRequest {

    @NotBlank(message = "cardNumber is required")
    private String cardNumber;

    private String cardholderName;

    @NotBlank(message = "expiry is required")
    @Pattern(regexp = "^(0[1-9]|1[0-2])\\/\\d{2,4}$", message = "expiry must be MM/YY or MM/YYYY")
    private String expiry;

    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }

    public String getCardholderName() { return cardholderName; }
    public void setCardholderName(String cardholderName) { this.cardholderName = cardholderName; }

    public String getExpiry() { return expiry; }
    public void setExpiry(String expiry) { this.expiry = expiry; }
}

