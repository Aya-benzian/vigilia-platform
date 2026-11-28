package com.vigilia.notification.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.vigilia.notification.dto.*;
import com.vigilia.notification.security.SecurityContextHelper;
import com.vigilia.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Customer notifications, unread counts, event ingestion, and preferences")
public class NotificationController {

    private final NotificationService notificationService;
    private final SecurityContextHelper securityContextHelper;

    public NotificationController(
            NotificationService notificationService,
            SecurityContextHelper securityContextHelper) {
        this.notificationService = notificationService;
        this.securityContextHelper = securityContextHelper;
    }

    @GetMapping
    @Operation(summary = "List notifications for authenticated user")
    public ResponseEntity<ApiResponse<NotificationListResponse>> listNotifications(
            HttpServletRequest request,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        NotificationListResponse response = notificationService.listNotifications(
                userId, category, status, search, page, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get unread notification count for authenticated user")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        return ResponseEntity.ok(ApiResponse.of(notificationService.getUnreadCount(userId)));
    }

    @PostMapping
    @Operation(summary = "Ingest notification event (M3 -> M4 contract)")
    public ResponseEntity<ApiResponse<NotificationResponse>> sendNotification(
            @Valid @RequestBody NotificationCreateRequest payload) {
        NotificationResponse created = notificationService.createFromEvent(payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(created));
    }

    @PostMapping("/events")
    @Operation(summary = "Ingest transaction outbox relay event")
    public ResponseEntity<ApiResponse<NotificationResponse>> ingestOutboxEvent(
            @RequestBody JsonNode payload) {
        NotificationResponse created = notificationService.createFromOutboxEvent(payload);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(created));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark a notification as read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            HttpServletRequest request,
            @PathVariable UUID id) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        return ResponseEntity.ok(ApiResponse.of(notificationService.markAsRead(userId, id)));
    }

    @PatchMapping("/{id}/unread")
    @Operation(summary = "Mark a notification as unread")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsUnread(
            HttpServletRequest request,
            @PathVariable UUID id) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        return ResponseEntity.ok(ApiResponse.of(notificationService.markAsUnread(userId, id)));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllAsRead(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        int updated = notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("updatedCount", updated)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Dismiss a notification")
    public ResponseEntity<ApiResponse<Void>> dismissNotification(
            HttpServletRequest request,
            @PathVariable UUID id) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        notificationService.dismissNotification(userId, id);
        return ResponseEntity.ok(ApiResponse.of(null, Map.of("message", "Notification dismissed")));
    }

    @DeleteMapping("/clear-read")
    @Operation(summary = "Clear all read notifications")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> clearReadNotifications(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        int deleted = notificationService.clearReadNotifications(userId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("deletedCount", deleted)));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Get notification preferences for authenticated user")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> getPreferences(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        return ResponseEntity.ok(ApiResponse.of(notificationService.getPreferences(userId)));
    }

    @PutMapping("/preferences")
    @Operation(summary = "Update notification preferences for authenticated user")
    public ResponseEntity<ApiResponse<NotificationPreferenceResponse>> updatePreferences(
            HttpServletRequest request,
            @RequestBody NotificationPreferenceUpdateRequest updateRequest) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        return ResponseEntity.ok(ApiResponse.of(notificationService.updatePreferences(userId, updateRequest)));
    }
}

