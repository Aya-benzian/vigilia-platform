package com.vigilia.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing customer notification and language preferences.
 * Schema: customer | Table: customer_preferences
 */
@Entity
@Table(name = "customer_preferences")
public class CustomerPreferenceEntity {

    @Id
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "email_notifications", nullable = false)
    private Boolean emailNotifications = true;

    @Column(name = "in_app_notifications", nullable = false)
    private Boolean inAppNotifications = true;

    @Column(name = "language", nullable = false, length = 10)
    private String language = "en";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CustomerPreferenceEntity() {
    }

    public CustomerPreferenceEntity(UUID customerId) {
        this.customerId = customerId;
        this.emailNotifications = true;
        this.inAppNotifications = true;
        this.language = "en";
        this.updatedAt = Instant.now();
    }

    public CustomerPreferenceEntity(UUID customerId, Boolean emailNotifications, Boolean inAppNotifications, String language) {
        this.customerId = customerId;
        this.emailNotifications = emailNotifications != null ? emailNotifications : true;
        this.inAppNotifications = inAppNotifications != null ? inAppNotifications : true;
        this.language = language != null && !language.isBlank() ? language : "en";
        this.updatedAt = Instant.now();
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
