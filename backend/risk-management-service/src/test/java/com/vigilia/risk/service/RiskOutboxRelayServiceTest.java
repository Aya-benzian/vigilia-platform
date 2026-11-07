package com.vigilia.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.entity.OutboxEventEntity;
import com.vigilia.risk.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskOutboxRelayServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private RestClient transactionRestClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;
    private RiskOutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        relayService = new RiskOutboxRelayService(
                outboxEventRepository, objectMapper, transactionRestClient, true, 3
        );
    }

    private void setupMockSuccess() {
        when(transactionRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    private void setupMockFailure(Exception ex) {
        when(transactionRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(ex);
    }

    @Test
    @DisplayName("RISK_EVALUATION_COMPLETED est archivé comme PUBLISHED")
    void processEvent_RiskEvaluationCompleted_Archived() {
        OutboxEventEntity event = new OutboxEventEntity(
                "RISK_EVALUATION",
                UUID.randomUUID().toString(),
                "RISK_EVALUATION_COMPLETED",
                "{\"decision\":\"APPROVED\"}",
                "trace-1"
        );
        event.setId(UUID.randomUUID());

        relayService.processEvent(event);

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("REVIEW_DECISION_COMPLETED relayé avec succès vers Transaction Service")
    void processEvent_ReviewDecisionCompleted_Success() {
        UUID txnId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
                "REVIEW_DECISION",
                txnId.toString(),
                "REVIEW_DECISION_COMPLETED",
                "{\"transactionId\":\"" + txnId + "\",\"decision\":\"APPROVED\",\"reasonCode\":\"OK\",\"analystId\":\"analyst-1\"}",
                "trace-2"
        );
        event.setId(UUID.randomUUID());

        setupMockSuccess();

        relayService.processEvent(event);

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Payload mal formé marque l'événement comme FAILED")
    void processEvent_MalformedPayload_MarksFailed() {
        OutboxEventEntity event = new OutboxEventEntity(
                "REVIEW_DECISION",
                UUID.randomUUID().toString(),
                "REVIEW_DECISION_COMPLETED",
                "not-valid-json",
                "trace-3"
        );
        event.setId(UUID.randomUUID());

        relayService.processEvent(event);

        assertThat(event.getStatus()).isEqualTo("FAILED");
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Échec HTTP incrémente les tentatives et calcule le backoff")
    void processEvent_HttpError_SchedulesRetry() {
        UUID txnId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
                "REVIEW_DECISION",
                txnId.toString(),
                "REVIEW_DECISION_COMPLETED",
                "{\"transactionId\":\"" + txnId + "\",\"decision\":\"APPROVED\"}",
                "trace-4"
        );
        event.setId(UUID.randomUUID());
        event.setAttempts(0);

        setupMockFailure(new RestClientException("Connection refused"));

        relayService.processEvent(event);

        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Tentatives maximales atteintes basculent l'événement en FAILED")
    void processEvent_MaxAttemptsExceeded_MarksFailed() {
        UUID txnId = UUID.randomUUID();
        OutboxEventEntity event = new OutboxEventEntity(
                "REVIEW_DECISION",
                txnId.toString(),
                "REVIEW_DECISION_COMPLETED",
                "{\"transactionId\":\"" + txnId + "\",\"decision\":\"APPROVED\"}",
                "trace-5"
        );
        event.setId(UUID.randomUUID());
        event.setAttempts(2); // maxAttempts = 3, next will be 3 -> FAILED

        setupMockFailure(new RestClientException("Service unavailable"));

        relayService.processEvent(event);

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo("FAILED");
        verify(outboxEventRepository).save(event);
    }
}
