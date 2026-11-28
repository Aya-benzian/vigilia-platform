package com.vigilia.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing customer wallet balance information.
 */
public class WalletResponse {

    private UUID id;
    private UUID customerId;
    private String accountNumber;
    private String currency;
    private BigDecimal availableBalance;
    private BigDecimal heldBalance;
    private BigDecimal totalBalance;
    private boolean cardFrozen;
    private Instant updatedAt;

    public WalletResponse() {
    }

    public WalletResponse(UUID id, UUID customerId, String accountNumber, String currency, BigDecimal availableBalance, BigDecimal heldBalance, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.availableBalance = availableBalance != null ? availableBalance : BigDecimal.ZERO;
        this.heldBalance = heldBalance != null ? heldBalance : BigDecimal.ZERO;
        this.totalBalance = this.availableBalance.add(this.heldBalance);
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(BigDecimal availableBalance) {
        this.availableBalance = availableBalance;
        if (this.availableBalance != null && this.heldBalance != null) {
            this.totalBalance = this.availableBalance.add(this.heldBalance);
        }
    }

    public BigDecimal getHeldBalance() {
        return heldBalance;
    }

    public void setHeldBalance(BigDecimal heldBalance) {
        this.heldBalance = heldBalance;
        if (this.availableBalance != null && this.heldBalance != null) {
            this.totalBalance = this.availableBalance.add(this.heldBalance);
        }
    }

    public BigDecimal getTotalBalance() {
        return totalBalance;
    }

    public void setTotalBalance(BigDecimal totalBalance) {
        this.totalBalance = totalBalance;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isCardFrozen() {
        return cardFrozen;
    }

    public void setCardFrozen(boolean cardFrozen) {
        this.cardFrozen = cardFrozen;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
