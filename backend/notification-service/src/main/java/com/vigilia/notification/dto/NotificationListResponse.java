package com.vigilia.notification.dto;

import java.util.List;
import java.util.Map;

public record NotificationListResponse(
        List<NotificationResponse> items,
        long unreadCount,
        long totalCount,
        Map<String, Long> categoryCounts,
        int page,
        int size,
        int totalPages
) {}

