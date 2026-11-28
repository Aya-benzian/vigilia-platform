package com.vigilia.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vigilia.notification.dto.NotificationResponse;
import com.vigilia.notification.entity.NotificationEntity;
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
}
