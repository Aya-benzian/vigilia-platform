package com.vigilia.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;
    private OutboxEventRelayService relayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        relayService = new OutboxEventRelayService(
                outboxEventRepository,
                objectMapper,
                restClient,
                true,
                5
        );
    }

    private void setupMockRestClientSuccess() {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private void setupMockRestClientFailure(Exception ex) {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(ex);
    }

    @Test
    @DisplayName("Relais réussi : événement USER_REGISTERED envoyé à Customer Service et marqué PUBLISHED")
    void testRelayPendingEventSuccess() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = String.format("{\"eventId\":\"%s\",\"eventType\":\"USER_REGISTERED\",\"userId\":\"%s\",\"email\":\"user@example.com\"}",
                eventId, userId);

        OutboxEventEntity event = new OutboxEventEntity("USER", userId.toString(), "USER_REGISTERED", payload, "trace-relay-1");
        event.setId(eventId);
        event.setStatus("PENDING");
        event.setAttempts(0);

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        setupMockRestClientSuccess();

        relayService.relayPendingEvents();

        assertEquals("PUBLISHED", event.getStatus());
        assertNotNull(event.getPublishedAt());
        verify(outboxEventRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Panne temporaire de Customer Service : tentative incrémentée, backoff calculé, statut reste PENDING")
    void testRelayTemporaryFailureRetriesWithBackoff() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = String.format("{\"eventId\":\"%s\",\"eventType\":\"USER_REGISTERED\",\"userId\":\"%s\",\"email\":\"user@example.com\"}",
                eventId, userId);

        OutboxEventEntity event = new OutboxEventEntity("USER", userId.toString(), "USER_REGISTERED", payload, "trace-relay-2");
        event.setId(eventId);
        event.setStatus("PENDING");
        event.setAttempts(1);

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        setupMockRestClientFailure(new RestClientException("Connection refused to Customer Service"));

        relayService.relayPendingEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(2, event.getAttempts());
        assertTrue(event.getNextAttemptAt().isAfter(Instant.now()));
        assertNull(event.getPublishedAt());
        verify(outboxEventRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Nombre maximal de tentatives atteint : événement marqué FAILED")
    void testRelayMaxAttemptsReachedMarksFailed() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = String.format("{\"eventId\":\"%s\",\"eventType\":\"USER_REGISTERED\",\"userId\":\"%s\",\"email\":\"user@example.com\"}",
                eventId, userId);

        OutboxEventEntity event = new OutboxEventEntity("USER", userId.toString(), "USER_REGISTERED", payload, "trace-relay-3");
        event.setId(eventId);
        event.setStatus("PENDING");
        event.setAttempts(4); // Next attempt will be 5 (maxAttempts = 5)

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(event));

        setupMockRestClientFailure(new RestClientException("Service still unavailable"));

        relayService.relayPendingEvents();

        assertEquals("FAILED", event.getStatus());
        assertEquals(5, event.getAttempts());
        assertNull(event.getPublishedAt());
        verify(outboxEventRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Payload JSON mal formé : marqué FAILED immédiatement sans bloquer le relais")
    void testMalformedPayloadMarksFailedWithoutCrashing() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity malformedEvent = new OutboxEventEntity("USER", "uid", "USER_REGISTERED", "INVALID_NON_JSON_CONTENT", "trace-bad");
        malformedEvent.setId(eventId);
        malformedEvent.setStatus("PENDING");

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(malformedEvent));

        relayService.relayPendingEvents();

        assertEquals("FAILED", malformedEvent.getStatus());
        verify(outboxEventRepository, times(1)).save(malformedEvent);
        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("Événement non-USER_REGISTERED ignoré par le relais Customer")
    void testNonRegistrationEventIgnored() {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity otherEvent = new OutboxEventEntity("USER", "uid", "PASSWORD_CHANGED", "{\"eventId\":\"123\"}", "trace-pwd");
        otherEvent.setId(eventId);
        otherEvent.setStatus("PENDING");

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(eq("PENDING"), any(Instant.class)))
                .thenReturn(List.of(otherEvent));

        relayService.relayPendingEvents();

        assertEquals("PENDING", otherEvent.getStatus());
        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("Relais désactivé : aucune recherche ni traitement d'événement")
    void testRelayDisabled() {
        OutboxEventRelayService disabledRelay = new OutboxEventRelayService(
                outboxEventRepository,
                objectMapper,
                restClient,
                false,
                5
        );

        disabledRelay.relayPendingEvents();

        verifyNoInteractions(outboxEventRepository);
        verifyNoInteractions(restClient);
    }
}
