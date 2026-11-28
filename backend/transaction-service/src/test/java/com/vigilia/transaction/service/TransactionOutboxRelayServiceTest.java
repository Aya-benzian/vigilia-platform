package com.vigilia.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.entity.OutboxEventEntity;
import com.vigilia.transaction.repository.OutboxEventRepository;
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
class TransactionOutboxRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private TransactionOutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Standalone mode: null restClientBuilder, downstreamUrl empty
        relayService = new TransactionOutboxRelayService(
                outboxEventRepository, objectMapper, null, true, 5, ""
        );
    }

    @Test
    @DisplayName("relayPendingEvents traite et publie les événements en attente")
    void relayPendingEvents_Success() {
        OutboxEventEntity event = new OutboxEventEntity(
                "TRANSACTION",
                UUID.randomUUID().toString(),
                "TRANSACTION_COMPLETED",
                "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"APPROVED\"}",
                "trace-1"
        );
        event.setId(UUID.randomUUID());

        when(outboxEventRepository.findPendingReadyForUpdate(any()))
                .thenReturn(List.of(event));

        relayService.relayPendingEvents();

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Type non supporté est ignoré")
    void processEvent_UnsupportedType_Ignored() {
        OutboxEventEntity event = new OutboxEventEntity(
                "TRANSACTION",
                UUID.randomUUID().toString(),
                "UNKNOWN_TYPE",
                "{\"data\":\"test\"}",
                "trace-2"
        );

        relayService.deliverAndUpdate(event);

        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Payload JSON invalide marque l'événement comme FAILED")
    void processEvent_InvalidPayload_MarksFailed() {
        OutboxEventEntity event = new OutboxEventEntity(
                "TRANSACTION",
                UUID.randomUUID().toString(),
                "TRANSACTION_COMPLETED",
                "invalid-json",
                "trace-3"
        );

        relayService.deliverAndUpdate(event);

        assertThat(event.getStatus()).isEqualTo("FAILED");
        verify(outboxEventRepository).save(event);
    }
}
