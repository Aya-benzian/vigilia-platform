package com.vigilia.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.repository.OutboxEventRepository;
import com.vigilia.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
class AuthRegistrationOutboxIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRelayService outboxEventRelayService;

    @MockBean
    private RestClient customerRestClient;

    private RestClient.RequestBodyUriSpec mockUriSpec;
    private RestClient.RequestBodySpec mockBodySpec;
    private RestClient.ResponseSpec mockResponseSpec;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();

        mockUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        mockBodySpec = mock(RestClient.RequestBodySpec.class);
        mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(customerRestClient.post()).thenReturn(mockUriSpec);
        when(mockUriSpec.uri(anyString())).thenReturn(mockBodySpec);
        when(mockBodySpec.contentType(any())).thenReturn(mockBodySpec);
        when(mockBodySpec.header(anyString(), anyString())).thenReturn(mockBodySpec);
        when(mockBodySpec.body(any(Object.class))).thenReturn(mockBodySpec);
        when(mockBodySpec.retrieve()).thenReturn(mockResponseSpec);
    }

    @Test
    @DisplayName("Flux complet : Inscription -> Outbox PENDING -> Relais vers Customer -> Statut PUBLISHED")
    void testCompleteRegistrationToOutboxPublishedFlow() {
        when(mockResponseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        // 1. Inscription utilisateur
        authService.register(
                new RegisterRequest("integration.user@example.com", "Password123!"),
                "127.0.0.1",
                "trace-e2e-1"
        );

        // 2. Vérification que l'événement USER_REGISTERED est persisté dans outbox_events avec statut PENDING
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEventEntity event = events.get(0);
        assertThat(event.getEventType()).isEqualTo("USER_REGISTERED");
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getAttempts()).isEqualTo(0);
        assertThat(event.getPublishedAt()).isNull();

        // 3. Exécution du relais Outbox
        outboxEventRelayService.relayPendingEvents();

        // 4. Vérification que l'événement est maintenant PUBLISHED avec published_at
        OutboxEventEntity updatedEvent = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getStatus()).isEqualTo("PUBLISHED");
        assertThat(updatedEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Résilience : Panne temporaire de Customer Service puis succès au retry")
    void testTemporaryFailurePreservesEventAndSucceedsOnRetry() {
        // 1. Simuler une panne de Customer Service
        when(mockResponseSpec.toBodilessEntity()).thenThrow(new RestClientException("503 Service Unavailable"));

        authService.register(
                new RegisterRequest("retry.user@example.com", "Password123!"),
                "127.0.0.1",
                "trace-e2e-2"
        );

        // 2. Exécution du relais : échec
        outboxEventRelayService.relayPendingEvents();

        // L'événement ne doit pas être perdu : statut PENDING, tentative = 1, nextAttemptAt dans le futur
        OutboxEventEntity failedEvent = outboxEventRepository.findAll().get(0);
        assertThat(failedEvent.getStatus()).isEqualTo("PENDING");
        assertThat(failedEvent.getAttempts()).isEqualTo(1);
        assertThat(failedEvent.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(failedEvent.getPublishedAt()).isNull();

        // 3. Le service Customer redevient disponible
        doReturn(ResponseEntity.ok().build()).when(mockResponseSpec).toBodilessEntity();

        // Forcer nextAttemptAt à maintenant pour le retry
        failedEvent.setNextAttemptAt(Instant.now().minusSeconds(5));
        outboxEventRepository.save(failedEvent);

        // 4. Seconde exécution du relais : succès
        outboxEventRelayService.relayPendingEvents();

        OutboxEventEntity recoveredEvent = outboxEventRepository.findById(failedEvent.getId()).orElseThrow();
        assertThat(recoveredEvent.getStatus()).isEqualTo("PUBLISHED");
        assertThat(recoveredEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Isolation : un événement mal formé devient FAILED sans bloquer un événement valide")
    void testMalformedEventBecomesFailedWithoutBlockingValidEvents() {
        doReturn(ResponseEntity.ok().build()).when(mockResponseSpec).toBodilessEntity();

        // Créer un événement mal formé (JSON valide en base mais incomplet / sans userId)
        OutboxEventEntity malformed = new OutboxEventEntity(
                "USER", "uid-bad", "USER_REGISTERED", "{\"invalidField\":true}", "trace-bad"
        );
        malformed.setNextAttemptAt(Instant.now().minusSeconds(5));
        outboxEventRepository.save(malformed);

        // Créer un événement valide via inscription
        authService.register(
                new RegisterRequest("valid.user@example.com", "Password123!"),
                "127.0.0.1",
                "trace-e2e-valid"
        );

        // Exécuter le relais
        outboxEventRelayService.relayPendingEvents();

        // Vérifier l'événement mal formé
        OutboxEventEntity processedMalformed = outboxEventRepository.findById(malformed.getId()).orElseThrow();
        assertThat(processedMalformed.getStatus()).isEqualTo("FAILED");

        // Vérifier que l'événement valide a été correctement publié malgré l'erreur du premier
        List<OutboxEventEntity> allEvents = outboxEventRepository.findAll();
        assertThat(allEvents).hasSize(2);
        OutboxEventEntity validEvent = allEvents.stream()
                .filter(e -> !e.getId().equals(malformed.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(validEvent.getStatus()).isEqualTo("PUBLISHED");
    }
}
