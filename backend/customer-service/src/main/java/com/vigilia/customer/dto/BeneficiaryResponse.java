package com.vigilia.customer.dto;

import java.time.Instant;
import java.util.UUID;

public class BeneficiaryResponse {

    private UUID id;
    private UUID customerId;
    private UUID recipientCustomerId;
    private String recipientAccountNumber;
    private String nickname;
    private Instant createdAt;

    public BeneficiaryResponse() {
    }

    public BeneficiaryResponse(UUID id, UUID customerId, UUID recipientCustomerId,
                               String recipientAccountNumber, String nickname, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.recipientCustomerId = recipientCustomerId;
        this.recipientAccountNumber = recipientAccountNumber;
        this.nickname = nickname;
        this.createdAt = createdAt;
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
