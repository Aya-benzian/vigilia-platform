package com.vigilia.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a Customer Wallet for storing and holding funds.
 * Schema: transaction | Table: wallets
 */
@Entity
@Table(name = "wallets", schema = "transaction")
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "customer_id", unique = true, nullable = false)
    private UUID customerId;

    @Column(name = "account_number", unique = true, nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "available_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(name = "held_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal heldBalance = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "card_frozen", nullable = false)
    private boolean cardFrozen = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public WalletEntity() {
    }

    public WalletEntity(UUID customerId, String accountNumber, String currency, BigDecimal availableBalance) {
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.availableBalance = availableBalance != null ? availableBalance : BigDecimal.ZERO;
        this.heldBalance = BigDecimal.ZERO;
        this.version = 0L;
        this.updatedAt = Instant.now();
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
    }

    public BigDecimal getHeldBalance() {
        return heldBalance;
    }

    public void setHeldBalance(BigDecimal heldBalance) {
        this.heldBalance = heldBalance;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
