package com.vigilia.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vigilia.notification.dto.NotificationResponse;
import com.vigilia.notification.dto.NotificationCreateRequest;
import com.vigilia.notification.dto.NotificationListResponse;
import com.vigilia.notification.dto.NotificationPreferenceUpdateRequest;
import com.vigilia.notification.dto.UnreadCountResponse;
import com.vigilia.notification.entity.NotificationEntity;
import com.vigilia.notification.entity.NotificationPreferenceEntity;
import com.vigilia.notification.entity.ProcessedEventEntity;
import com.vigilia.notification.repository.NotificationPreferenceRepository;
import com.vigilia.notification.repository.NotificationRepository;
import com.vigilia.notification.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Part B §8i — Unit tests for NotificationService outbox handlers
 * (TRANSFER_REVIEW_REQUIRED, PAYMENT_REVIEW_REQUIRED, and idempotency via ProcessedEventRepository).
 */
class NotificationServiceReviewTest {

    private NotificationRepository notificationRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private ProcessedEventRepository processedEventRepository;
    private ObjectMapper objectMapper;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        objectMapper = new ObjectMapper();

        notificationService = new NotificationService(
                notificationRepository,
                preferenceRepository,
                objectMapper,
                null,
                processedEventRepository
        );

        when(preferenceRepository.findById(any())).thenReturn(Optional.empty());
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notificationRepository.save(any(NotificationEntity.class))).thenAnswer(inv -> {
            NotificationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
    }

    @Test
    @DisplayName("TRANSFER_REVIEW_REQUIRED creates 'Transfer Under Review' notification and records processed event")
    void transferReviewRequired_createsNotification() {
        UUID customerId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", "TRANSFER_REVIEW_REQUIRED");
        payload.put("customerId", customerId.toString());
        payload.put("transactionId", txnId.toString());
        payload.put("transactionType", "TRANSFER");
        payload.put("amount", "250.00");
        payload.put("currency", "EUR");
        payload.put("reason", "HIGH_UNCERTAINTY");

        NotificationResponse response = notificationService.createFromOutboxEvent(payload);

        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Transfer Under Review");
        assertThat(response.message()).contains("transfer", "250.00 EUR", "under review", "HIGH_UNCERTAINTY");
        assertThat(response.eventType()).isEqualTo("TRANSFER_REVIEW_REQUIRED");

        ArgumentCaptor<ProcessedEventEntity> processedCaptor = ArgumentCaptor.forClass(ProcessedEventEntity.class);
        verify(processedEventRepository).save(processedCaptor.capture());
        assertThat(processedCaptor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(processedCaptor.getValue().getEventType()).isEqualTo("TRANSFER_REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("PAYMENT_REVIEW_REQUIRED creates 'Payment Under Review' notification and records processed event")
    void paymentReviewRequired_createsNotification() {
        UUID customerId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", "PAYMENT_REVIEW_REQUIRED");
        payload.put("customerId", customerId.toString());
        payload.put("transactionId", txnId.toString());
        payload.put("transactionType", "PURCHASE");
        payload.put("amount", "99.50");
        payload.put("currency", "EUR");
        payload.put("reason", "VELOCITY_ALERT");

        NotificationResponse response = notificationService.createFromOutboxEvent(payload);

        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Payment Under Review");
        assertThat(response.message()).contains("payment", "99.50 EUR", "under review", "VELOCITY_ALERT");
        assertThat(response.eventType()).isEqualTo("PAYMENT_REVIEW_REQUIRED");

        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    }

    @Test
    @DisplayName("Duplicate eventId is deduplicated via ProcessedEventRepository without saving a second notification")
    void duplicateEvent_isDeduplicated() {
        UUID customerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        when(processedEventRepository.existsById(eventId)).thenReturn(true);
        NotificationEntity existing = new NotificationEntity();
        existing.setId(UUID.randomUUID());
        existing.setUserId(customerId);
        existing.setEventId(eventId);
        existing.setEventType("PAYMENT_REVIEW_REQUIRED");
        existing.setTitle("Payment Under Review");
        existing.setMessage("Already stored");
        when(notificationRepository.findByEventId(eventId)).thenReturn(Optional.of(existing));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", "PAYMENT_REVIEW_REQUIRED");
        payload.put("customerId", customerId.toString());

        NotificationResponse response = notificationService.createFromOutboxEvent(payload);

        assertThat(response).isNotNull();
        assertThat(response.title()).isEqualTo("Payment Under Review");
        verify(notificationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("listNotifications filters, paginates, and returns category counts")
    void listNotifications_filtersAndPaginates() {
        UUID customerId = UUID.randomUUID();
        NotificationEntity unread = notification(customerId, "TRANSFER_REVIEW_REQUIRED", "REVIEW", false);
        unread.setTitle("Transfer review");
        unread.setMessage("A transfer needs attention");
        NotificationEntity read = notification(customerId, "TRANSACTION_APPROVED", "TRANSACTION", true);
        read.setTitle("Payment complete");
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(customerId))
                .thenReturn(List.of(unread, read));

        NotificationListResponse response = notificationService.listNotifications(
                customerId, "REVIEW", "UNREAD", "attention", 0, 10);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().title()).isEqualTo("Transfer review");
        assertThat(response.unreadCount()).isEqualTo(1);
        assertThat(response.categoryCounts()).containsEntry("REVIEW", 1L);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("notification read state and bulk operations delegate to repositories")
    void readStateAndBulkOperations_arePersisted() {
        UUID customerId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        NotificationEntity entity = notification(customerId, "TRANSACTION_APPROVED", "TRANSACTION", false);
        entity.setId(notificationId);
        when(notificationRepository.findByIdAndUserId(notificationId, customerId)).thenReturn(Optional.of(entity));
        when(notificationRepository.save(entity)).thenReturn(entity);

        notificationService.markAsRead(customerId, notificationId);
        assertThat(entity.isRead()).isTrue();
        notificationService.markAsUnread(customerId, notificationId);
        assertThat(entity.isRead()).isFalse();

        notificationService.markAllAsRead(customerId);
        notificationService.dismissNotification(customerId, notificationId);
        notificationService.clearReadNotifications(customerId);
        verify(notificationRepository).markAllAsReadByUserId(eq(customerId), any(Instant.class));
        verify(notificationRepository).delete(entity);
        verify(notificationRepository).deleteAllReadByUserId(customerId);
    }

    @Test
    @DisplayName("preferences are created, updated, and unread counts are returned")
    void preferencesAndUnreadCount_areMapped() {
        UUID customerId = UUID.randomUUID();
        when(notificationRepository.countByUserIdAndReadFalse(customerId)).thenReturn(4L);
        when(notificationRepository.countByUserIdAndReadFalseAndSeverityIn(eq(customerId), any()))
                .thenReturn(2L);
        UnreadCountResponse counts = notificationService.getUnreadCount(customerId);
        assertThat(counts.unreadCount()).isEqualTo(4L);
        assertThat(counts.criticalCount()).isEqualTo(2L);

        NotificationPreferenceEntity prefs = new NotificationPreferenceEntity();
        prefs.setUserId(customerId);
        when(preferenceRepository.findById(customerId)).thenReturn(Optional.of(prefs));
        when(preferenceRepository.save(prefs)).thenReturn(prefs);
        NotificationPreferenceUpdateRequest update = new NotificationPreferenceUpdateRequest(
                false, true, false, true, false, true);
        assertThat(notificationService.updatePreferences(customerId, update).transactionUpdates()).isFalse();
        assertThat(notificationService.getPreferences(customerId).securityAlerts()).isTrue();
    }

    @Test
    @DisplayName("createFromEvent stores normalized metadata and channel")
    void createFromEvent_normalizesAndStoresRequest() {
        UUID customerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        NotificationEntity saved = notification(customerId, "LOGIN_SUCCESS", "SECURITY", false);
        when(notificationRepository.save(any(NotificationEntity.class))).thenAnswer(invocation -> {
            NotificationEntity value = invocation.getArgument(0);
            value.setId(saved.getId());
            return value;
        });

        NotificationResponse response = notificationService.createFromEvent(new NotificationCreateRequest(
                eventId, " login_success ", Instant.now(), customerId, null, " email ",
                " Login ", " Signed in ", Map.of("ip", "127.0.0.1")));

        assertThat(response.eventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(response.category()).isEqualTo("SECURITY");
        assertThat(response.channel()).isEqualTo("EMAIL");
        verify(processedEventRepository).save(any());
    }

    private NotificationEntity notification(UUID userId, String eventType, String category, boolean read) {
        NotificationEntity entity = new NotificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setEventId(UUID.randomUUID());
        entity.setEventType(eventType);
        entity.setCategory(category);
        entity.setSeverity("INFO");
        entity.setChannel("IN_APP");
        entity.setTitle(eventType);
        entity.setMessage(eventType);
        entity.setRead(read);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
