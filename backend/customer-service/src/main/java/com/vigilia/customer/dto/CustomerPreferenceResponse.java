package com.vigilia.customer.dto;

import java.time.Instant;
import java.util.UUID;

public class CustomerPreferenceResponse {

    private UUID customerId;
    private Boolean emailNotifications;
    private Boolean inAppNotifications;
    private String language;
    private Instant updatedAt;

    public CustomerPreferenceResponse() {
    }

    public CustomerPreferenceResponse(UUID customerId, Boolean emailNotifications, Boolean inAppNotifications, String language, Instant updatedAt) {
        this.customerId = customerId;
        this.emailNotifications = emailNotifications;
        this.inAppNotifications = inAppNotifications;
        this.language = language;
        this.updatedAt = updatedAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public Boolean getEmailNotifications() {
        return emailNotifications;
    }

    public void setEmailNotifications(Boolean emailNotifications) {
        this.emailNotifications = emailNotifications;
    }

    public Boolean getInAppNotifications() {
        return inAppNotifications;
    }

    public void setInAppNotifications(Boolean inAppNotifications) {
        this.inAppNotifications = inAppNotifications;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
