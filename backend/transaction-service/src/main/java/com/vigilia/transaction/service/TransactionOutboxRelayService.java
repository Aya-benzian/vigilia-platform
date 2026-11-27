package com.vigilia.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.entity.OutboxEventEntity;
import com.vigilia.transaction.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Asynchronous Outbox Event Relay service for Transaction Service.
 * Guarantees reliable publishing of TRANSACTION_COMPLETED, TRANSACTION_REJECTED,
 * and TRANSACTION_RESOLVED events from transaction_schema.outbox_events with retry backoff.
 */
@Service
public class TransactionOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxRelayService.class);

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "TRANSACTION_COMPLETED",
            "TRANSACTION_REJECTED",
            "TRANSACTION_RESOLVED",
            "TRANSACTION_PENDING_REVIEW"  // E2 fix: relay pending-review events to downstream
    );

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean relayEnabled;
    private final int maxAttempts;
    private final String downstreamUrl;

    @Autowired
    public TransactionOutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) RestClient.Builder restClientBuilder,
            @Value("${transaction.outbox.relay.enabled:true}") boolean relayEnabled,
            @Value("${transaction.outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${transaction.outbox.relay.downstream-url:}") String downstreamUrl) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.relayEnabled = relayEnabled;
        this.maxAttempts = maxAttempts;
        this.downstreamUrl = (downstreamUrl != null) ? downstreamUrl.trim() : "";
        this.restClient = (restClientBuilder != null && !this.downstreamUrl.isEmpty())
                ? restClientBuilder.baseUrl(this.downstreamUrl).build()
                : null;
    }

    @Scheduled(fixedDelayString = "${transaction.outbox.relay.delay-ms:2000}")
    public void relayPendingEvents() {
        if (!relayEnabled) {
            return;
        }

        // H4 fix: use FOR UPDATE SKIP LOCKED so multiple relay instances never pick the same row
        List<OutboxEventEntity> pendingEvents = outboxEventRepository
                .findPendingReadyForUpdate(Instant.now());

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("[TRANSACTION-OUTBOX] Traitement de {} événement(s) Outbox en attente", pendingEvents.size());

        for (OutboxEventEntity event : pendingEvents) {
            try {
                processEvent(event);
            } catch (Exception ex) {
                log.error("[TRANSACTION-OUTBOX] Erreur inattendue lors du traitement de l'événement [id={}]: {}",
                        event.getId(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processEvent(OutboxEventEntity event) {
        String eventType = event.getEventType();
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
            log.debug("[TRANSACTION-OUTBOX] Type d'événement ignoré [type={}, id={}]", eventType, event.getId());
            return;
        }

        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
            if (!payloadNode.isObject() || !payloadNode.has("transactionId")) {
                throw new IllegalArgumentException("Payload transaction outbox incomplet (transactionId manquant)");
            }
        } catch (Exception ex) {
            log.error("[TRANSACTION-OUTBOX] Événement mal formé [id={}]. Marquage comme FAILED: {}",
                    event.getId(), ex.getMessage());
            event.setStatus("FAILED");
            outboxEventRepository.save(event);
            return;
        }

        String traceId = event.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // If a downstream consumer (e.g. notification-service, audit-service) is configured
        if (restClient != null && !downstreamUrl.isEmpty()) {
            try {
                restClient.post()
                        .uri("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", traceId)
                        .body(payloadNode)
                        .retrieve()
                        .toBodilessEntity();

                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);

                log.info("[TRANSACTION-OUTBOX] Événement {} livré vers downstream avec succès [id={}, txnId={}, traceId={}]",
                        eventType, event.getAggregateId(), traceId);
            } catch (Exception ex) {
                int attempts = (event.getAttempts() != null ? event.getAttempts() : 0) + 1;
                event.setAttempts(attempts);

                if (attempts >= maxAttempts) {
                    event.setStatus("FAILED");
                    log.error("[TRANSACTION-OUTBOX] Échec définitif de livraison [id={}, tentatives={}/{}]: {}",
                            event.getId(), attempts, maxAttempts, ex.getMessage());
                } else {
                    long backoffSeconds = 5L * (1L << Math.min(attempts - 1, 5));
                    event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
                    log.warn("[TRANSACTION-OUTBOX] Échec temporaire de livraison [id={}, tentative={}/{}, retryIn={}s]: {}",
                            event.getId(), attempts, maxAttempts, backoffSeconds, ex.getMessage());
                }
                outboxEventRepository.save(event);
            }
        } else {
            // Standalone mode: events recorded in outbox are marked as PUBLISHED for consumer polling / audit log
            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
            log.debug("[TRANSACTION-OUTBOX] Événement {} marqué comme PUBLISHED (mode standalone/audit) [id={}, txnId={}]",
                    eventType, event.getId(), event.getAggregateId());
        }
    }
}
