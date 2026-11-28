package com.vigilia.notification.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID userId,
        UUID eventId,
        String eventType,
        String category,
        String severity,
        String channel,
        String title,
        String message,
        UUID transactionId,
        Map<String, Object> metadata,
        boolean read,
        Instant readAt,
        Instant createdAt
) {}

