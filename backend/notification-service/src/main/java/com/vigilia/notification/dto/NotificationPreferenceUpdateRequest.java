package com.vigilia.notification.dto;

public record NotificationPreferenceUpdateRequest(
        Boolean transactionUpdates,
        Boolean securityAlerts,
        Boolean transferReviews,
        Boolean promotionalUpdates,
        Boolean emailNotifications,
        Boolean inAppNotifications
) {}

