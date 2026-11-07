package com.vigilia.risk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vigilia.risk.entity.OutboxEventEntity;
import com.vigilia.risk.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Asynchronous Outbox Event Relay service for Risk Management Service.
 * Guarantees at-least-once delivery of REVIEW_DECISION_COMPLETED events
 * to Transaction Service (/internal/transactions/events) with exponential retry backoff.
 */
@Service
public class RiskOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(RiskOutboxRelayService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RestClient transactionRestClient;
    private final boolean relayEnabled;
    private final int maxAttempts;

    @Autowired
    public RiskOutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Qualifier("transactionRestClient") RestClient transactionRestClient,
            @Value("${risk.outbox.relay.enabled:true}") boolean relayEnabled,
            @Value("${risk.outbox.relay.max-attempts:5}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.transactionRestClient = transactionRestClient;
        this.relayEnabled = relayEnabled;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${risk.outbox.relay.delay-ms:2000}")
    public void relayPendingEvents() {
        if (!relayEnabled) {
            return;
        }

        List<OutboxEventEntity> pendingEvents = outboxEventRepository.findPendingReady(Instant.now());

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("[RISK-OUTBOX] Traitement de {} événement(s) Outbox prêts", pendingEvents.size());

        for (OutboxEventEntity event : pendingEvents) {
            try {
                processEvent(event);
            } catch (Exception ex) {
                log.error("[RISK-OUTBOX] Erreur inattendue lors du traitement de l'événement [id={}]: {}",
                        event.getId(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processEvent(OutboxEventEntity event) {
        String eventType = event.getEventType();

        // 1. RISK_EVALUATION_COMPLETED is currently kept for internal/audit purposes
        if ("RISK_EVALUATION_COMPLETED".equals(eventType)) {
            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
            log.debug("[RISK-OUTBOX] Événement d'évaluation de risque archivé comme PUBLISHED [id={}]", event.getId());
            return;
        }

        // 2. Only REVIEW_DECISION_COMPLETED needs cross-service HTTP delivery to Transaction Service
        if (!"REVIEW_DECISION_COMPLETED".equals(eventType)) {
            log.debug("[RISK-OUTBOX] Type d'événement ignoré [type={}, id={}]", eventType, event.getId());
            return;
        }

        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
            if (!payloadNode.isObject() || !payloadNode.has("transactionId") || !payloadNode.has("decision")) {
                throw new IllegalArgumentException("Payload de décision de revue invalide (transactionId ou decision manquant)");
            }

            // Ensure eventId and traceId are present in payload
            ObjectNode objectNode = (ObjectNode) payloadNode;
            if (!objectNode.has("eventId")) {
                objectNode.put("eventId", event.getId().toString());
            }
            if (!objectNode.has("traceId") && event.getTraceId() != null) {
                objectNode.put("traceId", event.getTraceId());
            }
        } catch (Exception ex) {
            log.error("[RISK-OUTBOX] Événement mal formé [id={}]. Marquage comme FAILED: {}",
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
            transactionRestClient.post()
                    .uri("/internal/transactions/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", traceId)
                    .body(payloadNode)
                    .retrieve()
                    .toBodilessEntity();

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);

            log.info("[RISK-OUTBOX] Événement REVIEW_DECISION_COMPLETED relayé vers Transaction Service avec succès [id={}, aggregateId={}, traceId={}]",
                    event.getId(), event.getAggregateId(), traceId);

        } catch (Exception ex) {
            int attempts = (event.getAttempts() != null ? event.getAttempts() : 0) + 1;
            event.setAttempts(attempts);

            if (attempts >= maxAttempts) {
                event.setStatus("FAILED");
                log.error("[RISK-OUTBOX] Échec définitif de livraison de l'événement [id={}, tentatives={}/{}]: {}",
                        event.getId(), attempts, maxAttempts, ex.getMessage());
            } else {
                long backoffSeconds = 5L * (1L << Math.min(attempts - 1, 5));
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                log.warn("[RISK-OUTBOX] Échec temporaire de livraison vers Transaction Service [id={}, tentative={}/{}, retryIn={}s]: {}",
                        event.getId(), attempts, maxAttempts, backoffSeconds, ex.getMessage());
            }

            outboxEventRepository.save(event);
        }
    }
}
