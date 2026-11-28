package com.vigilia.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationCreateRequest(
        UUID eventId,
        @NotBlank(message = "eventType is required") String eventType,
        Instant timestamp,
        @NotNull(message = "customerId is required") UUID customerId,
        UUID transactionId,
        String channel,
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "message is required") String message,
        Map<String, Object> metadata
) {}

