package com.vigilia.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous Outbox Event Relay service.
 * Guarantees at-least-once delivery of USER_REGISTERED events from auth.outbox_events
 * to Customer Service (/internal/customers/events) without distributed SQL transactions.
 */
@Service
public class OutboxEventRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRelayService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean relayEnabled;
    private final int maxAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxEventRelayService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            RestClient customerRestClient,
            @Value("${auth.outbox.relay.enabled:true}") boolean relayEnabled,
            @Value("${auth.outbox.relay.max-attempts:5}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.restClient = customerRestClient;
        this.relayEnabled = relayEnabled;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${auth.outbox.relay.delay-ms:2000}")
    public void relayPendingEvents() {
        if (!relayEnabled) {
            return;
        }

        List<OutboxEventEntity> pendingEvents = outboxEventRepository
                .findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc("PENDING", Instant.now().plusMillis(500));

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Traitement de {} événement(s) Outbox en attente", pendingEvents.size());

        for (OutboxEventEntity event : pendingEvents) {
            try {
                processEvent(event);
            } catch (Exception ex) {
                log.error("Erreur inattendue lors du traitement de l'événement Outbox [id={}]: {}",
                        event.getId(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processEvent(OutboxEventEntity event) {
        if (!"USER_REGISTERED".equals(event.getEventType())) {
            log.debug("Type d'événement ignoré par le relais Customer [type={}, id={}]",
                    event.getEventType(), event.getId());
            return;
        }

        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
            if (!payloadNode.isObject() || !payloadNode.has("userId")) {
                throw new IllegalArgumentException("Payload incomplet ou invalide (userId manquant)");
            }
            if (!payloadNode.has("traceId") && event.getTraceId() != null) {
                ((ObjectNode) payloadNode).put("traceId", event.getTraceId());
            }
        } catch (Exception ex) {
            log.error("Événement Outbox mal formé [id={}]. Marquage comme FAILED: {}",
                    event.getId(), ex.getMessage());
            event.setStatus("FAILED");
            outboxEventRepository.save(event);
            return;
        }

        String traceId = event.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        try {
            restClient.post()
                    .uri("/internal/customers/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", traceId)
                    .body(payloadNode)
                    .retrieve()
                    .toBodilessEntity();

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);

            log.info("Événement Outbox USER_REGISTERED publié vers Customer Service avec succès [id={}, aggregateId={}, traceId={}]",
                    event.getId(), event.getAggregateId(), traceId);

        } catch (Exception ex) {
            int attempts = (event.getAttempts() != null ? event.getAttempts() : 0) + 1;
            event.setAttempts(attempts);

            if (attempts >= maxAttempts) {
                event.setStatus("FAILED");
                log.error("Échec définitif de livraison de l'événement Outbox [id={}, tentatives={}/{}]: {}",
                        event.getId(), attempts, maxAttempts, ex.getMessage());
            } else {
                long backoffSeconds = 5L * (1L << Math.min(attempts - 1, 5));
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                log.warn("Échec temporaire de livraison vers Customer Service [id={}, tentative={}/{}, retryIn={}s]: {}",
                        event.getId(), attempts, maxAttempts, backoffSeconds, ex.getMessage());
            }

            outboxEventRepository.save(event);
        }
    }
}
