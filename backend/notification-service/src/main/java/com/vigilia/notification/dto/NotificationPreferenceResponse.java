package com.vigilia.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationPreferenceResponse(
        UUID userId,
        boolean transactionUpdates,
        boolean securityAlerts,
        boolean transferReviews,
        boolean promotionalUpdates,
        boolean emailNotifications,
        boolean inAppNotifications,
        Instant updatedAt
) {}

