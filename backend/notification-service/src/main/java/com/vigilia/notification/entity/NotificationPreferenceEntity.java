package com.vigilia.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreferenceEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_updates", nullable = false)
    private boolean transactionUpdates = true;

    @Column(name = "security_alerts", nullable = false)
    private boolean securityAlerts = true;

    @Column(name = "transfer_reviews", nullable = false)
    private boolean transferReviews = true;

    @Column(name = "promotional_updates", nullable = false)
    private boolean promotionalUpdates = false;

    @Column(name = "email_notifications", nullable = false)
    private boolean emailNotifications = true;

    @Column(name = "in_app_notifications", nullable = false)
    private boolean inAppNotifications = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onTouch() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public boolean isTransactionUpdates() { return transactionUpdates; }
    public void setTransactionUpdates(boolean transactionUpdates) { this.transactionUpdates = transactionUpdates; }

    public boolean isSecurityAlerts() { return securityAlerts; }
    public void setSecurityAlerts(boolean securityAlerts) { this.securityAlerts = securityAlerts; }

    public boolean isTransferReviews() { return transferReviews; }
    public void setTransferReviews(boolean transferReviews) { this.transferReviews = transferReviews; }

    public boolean isPromotionalUpdates() { return promotionalUpdates; }
    public void setPromotionalUpdates(boolean promotionalUpdates) { this.promotionalUpdates = promotionalUpdates; }

    public boolean isEmailNotifications() { return emailNotifications; }
    public void setEmailNotifications(boolean emailNotifications) { this.emailNotifications = emailNotifications; }

    public boolean isInAppNotifications() { return inAppNotifications; }
    public void setInAppNotifications(boolean inAppNotifications) { this.inAppNotifications = inAppNotifications; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

