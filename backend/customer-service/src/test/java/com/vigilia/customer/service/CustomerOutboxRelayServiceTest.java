package com.vigilia.customer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.customer.entity.OutboxEventEntity;
import com.vigilia.customer.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerOutboxRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private CustomerOutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        relayService = new CustomerOutboxRelayService(
                outboxEventRepository, objectMapper, null, true, 5, ""
        );
    }

    @Test
    @DisplayName("relayPendingEvents traite et publie les événements clients en attente")
    void relayPendingEvents_Success() {
        OutboxEventEntity event = new OutboxEventEntity(
                "CUSTOMER",
                UUID.randomUUID().toString(),
                "CUSTOMER_CREATED",
                "{\"customerId\":\"" + UUID.randomUUID() + "\",\"fullName\":\"John Doe\"}",
                "trace-1"
        );
        event.setId(UUID.randomUUID());

        when(outboxEventRepository.findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(event));

        relayService.relayPendingEvents();

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Type d'événement non supporté est ignoré")
    void processEvent_UnsupportedType_Ignored() {
        OutboxEventEntity event = new OutboxEventEntity(
                "CUSTOMER",
                UUID.randomUUID().toString(),
                "OTHER_EVENT",
                "{\"data\":\"test\"}",
                "trace-2"
        );

        relayService.processEvent(event);

        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Payload JSON invalide marque l'événement comme FAILED")
    void processEvent_InvalidPayload_MarksFailed() {
        OutboxEventEntity event = new OutboxEventEntity(
                "CUSTOMER",
                UUID.randomUUID().toString(),
                "CUSTOMER_CREATED",
                "invalid-json",
                "trace-3"
        );

        relayService.processEvent(event);

        assertThat(event.getStatus()).isEqualTo("FAILED");
        verify(outboxEventRepository).save(event);
    }
}
