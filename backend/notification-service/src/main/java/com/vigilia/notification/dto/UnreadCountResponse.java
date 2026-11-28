package com.vigilia.notification.dto;

public record UnreadCountResponse(
        long unreadCount,
        long criticalCount
) {}

