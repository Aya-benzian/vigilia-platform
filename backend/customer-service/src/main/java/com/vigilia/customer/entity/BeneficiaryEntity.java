package com.vigilia.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a saved beneficiary for peer-to-peer transfers.
 * Schema: customer | Table: beneficiaries
 */
@Entity
@Table(name = "beneficiaries", uniqueConstraints = {
        @UniqueConstraint(name = "uq_customer_beneficiary", columnNames = {"customer_id", "recipient_account_number"})
})
public class BeneficiaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "recipient_customer_id", nullable = false)
    private UUID recipientCustomerId;

    @Column(name = "recipient_account_number", nullable = false, length = 34)
    private String recipientAccountNumber;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public BeneficiaryEntity() {
    }

    public BeneficiaryEntity(UUID customerId, UUID recipientCustomerId, String recipientAccountNumber, String nickname) {
        this.customerId = customerId;
        this.recipientCustomerId = recipientCustomerId;
        this.recipientAccountNumber = recipientAccountNumber;
        this.nickname = nickname;
        this.createdAt = Instant.now();
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

    public UUID getRecipientCustomerId() {
        return recipientCustomerId;
    }

    public void setRecipientCustomerId(UUID recipientCustomerId) {
        this.recipientCustomerId = recipientCustomerId;
    }

    public String getRecipientAccountNumber() {
        return recipientAccountNumber;
    }

    public void setRecipientAccountNumber(String recipientAccountNumber) {
        this.recipientAccountNumber = recipientAccountNumber;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
