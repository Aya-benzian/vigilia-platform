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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Step 6 & Step 9: Asynchronous Outbox Event Relay service for Transaction Service.
 *
 * <p>Uses a lease-based claim pattern:
 * <ol>
 *   <li>In one short transaction: select ready PENDING events or expired IN_PROGRESS leases with
 *       {@code FOR UPDATE SKIP LOCKED}, mark them {@code IN_PROGRESS} with {@code locked_until = now + lease}.</li>
 *   <li>Deliver HTTP outside any database transaction so connection pools / row locks are not held.</li>
 *   <li>In a short transaction: mark {@code PUBLISHED} or reschedule ({@code PENDING} with backoff) / {@code FAILED}.</li>
 * </ol>
 */
@Service
public class TransactionOutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxRelayService.class);

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "TRANSACTION_COMPLETED",
            "TRANSACTION_REJECTED",
            "TRANSACTION_RESOLVED",
            "TRANSFER_REVIEW_REQUIRED",
            "PAYMENT_REVIEW_REQUIRED"
    );

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean relayEnabled;
    private final int maxAttempts;
    private final long leaseSeconds;
    private final String downstreamUrl;
    private final TransactionTemplate shortTx;

    public TransactionOutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            boolean relayEnabled,
            int maxAttempts,
            String downstreamUrl) {
        this(outboxEventRepository, objectMapper, restClientBuilder, null,
                relayEnabled, maxAttempts, 60L, downstreamUrl);
    }

    @Autowired
    public TransactionOutboxRelayService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) RestClient.Builder restClientBuilder,
            @Autowired(required = false) PlatformTransactionManager txManager,
            @Value("${transaction.outbox.relay.enabled:true}") boolean relayEnabled,
            @Value("${transaction.outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${transaction.outbox.relay.lease-seconds:60}") long leaseSeconds,
            @Value("${transaction.outbox.relay.downstream-url:}") String downstreamUrl) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.relayEnabled = relayEnabled;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = Math.max(5L, leaseSeconds);
        this.downstreamUrl = (downstreamUrl != null) ? downstreamUrl.trim() : "";
        this.restClient = (restClientBuilder != null && !this.downstreamUrl.isEmpty())
                ? restClientBuilder.baseUrl(this.downstreamUrl).build()
                : null;
        if (txManager != null) {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.shortTx = tt;
        } else {
            this.shortTx = null;
        }
    }

    @Scheduled(fixedDelayString = "${transaction.outbox.relay.delay-ms:2000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void relayPendingEvents() {
        if (!relayEnabled) {
            return;
        }

        // Step 6 Phase 1: short transaction to claim batch with SKIP LOCKED and set IN_PROGRESS + locked_until
        List<OutboxEventEntity> leasedEvents = claimLeaseBatch();
        if (leasedEvents == null || leasedEvents.isEmpty()) {
            return;
        }

        log.debug("[TRANSACTION-OUTBOX] Leased {} outbox event(s) for delivery", leasedEvents.size());

        // Step 6 Phase 2 & 3: deliver outside DB transaction, then persist final state in a short tx
        for (OutboxEventEntity event : leasedEvents) {
            try {
                deliverAndUpdate(event);
            } catch (Exception ex) {
                log.error("[TRANSACTION-OUTBOX] Unexpected error processing event [id={}]: {}",
                        event.getId(), ex.getMessage(), ex);
            }
        }
    }

    List<OutboxEventEntity> claimLeaseBatch() {
        if (shortTx == null) {
            return doClaimLeaseBatch();
        }
        return shortTx.execute(status -> doClaimLeaseBatch());
    }

    private List<OutboxEventEntity> doClaimLeaseBatch() {
        Instant now = Instant.now();
        List<OutboxEventEntity> ready = outboxEventRepository.findPendingReadyForUpdate(now);
        if (ready == null || ready.isEmpty()) {
            return Collections.emptyList();
        }
        Instant leaseUntil = now.plusSeconds(leaseSeconds);
        for (OutboxEventEntity event : ready) {
            event.setStatus("IN_PROGRESS");
            event.setLockedUntil(leaseUntil);
        }
        outboxEventRepository.saveAll(ready);
        return ready;
    }

    void deliverAndUpdate(OutboxEventEntity event) {
        String eventType = event.getEventType();
        if (!SUPPORTED_EVENT_TYPES.contains(eventType)) {
            log.debug("[TRANSACTION-OUTBOX] Ignored event type [type={}, id={}]", eventType, event.getId());
            return;
        }

        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
            if (!payloadNode.isObject() || !payloadNode.has("transactionId")) {
                throw new IllegalArgumentException("Incomplete transaction outbox payload (missing transactionId)");
            }
        } catch (Exception ex) {
            log.error("[TRANSACTION-OUTBOX] Malformed event [id={}]. Marking FAILED: {}",
                    event.getId(), ex.getMessage());
            markOutcome(event, "FAILED", null, null, null);
            return;
        }

        String traceId = event.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // Deliver HTTP OUTSIDE any DB transaction
        if (restClient != null && !downstreamUrl.isEmpty()) {
            try {
                restClient.post()
                        .uri("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", traceId)
                        .body(payloadNode)
                        .retrieve()
                        .toBodilessEntity();

                markOutcome(event, "PUBLISHED", Instant.now(), null, null);
                log.info("[TRANSACTION-OUTBOX] Event {} delivered downstream [id={}, txnId={}, traceId={}]",
                        eventType, event.getId(), event.getAggregateId(), traceId);
            } catch (Exception ex) {
                int attempts = (event.getAttempts() != null ? event.getAttempts() : 0) + 1;
                if (attempts >= maxAttempts) {
                    markOutcome(event, "FAILED", null, attempts, null);
                    log.error("[TRANSACTION-OUTBOX] Permanent delivery failure [id={}, attempts={}/{}]: {}",
                            event.getId(), attempts, maxAttempts, ex.getMessage());
                } else {
                    long backoffSeconds = 5L * (1L << Math.min(attempts - 1, 5));
                    Instant nextAttempt = Instant.now().plusSeconds(backoffSeconds);
                    markOutcome(event, "PENDING", null, attempts, nextAttempt);
                    log.warn("[TRANSACTION-OUTBOX] Temporary delivery failure [id={}, attempt={}/{}, retryIn={}s]: {}",
                            event.getId(), attempts, maxAttempts, backoffSeconds, ex.getMessage());
                }
            }
        } else {
            // Standalone mode: mark PUBLISHED
            markOutcome(event, "PUBLISHED", Instant.now(), null, null);
            log.debug("[TRANSACTION-OUTBOX] Event {} marked PUBLISHED (standalone) [id={}, txnId={}]",
                    eventType, event.getId(), event.getAggregateId());
        }
    }

    private void markOutcome(OutboxEventEntity event, String newStatus, Instant publishedAt,
                             Integer newAttempts, Instant nextAttemptAt) {
        if (shortTx == null) {
            applyOutcome(event, newStatus, publishedAt, newAttempts, nextAttemptAt);
        } else {
            shortTx.executeWithoutResult(status ->
                    applyOutcome(event, newStatus, publishedAt, newAttempts, nextAttemptAt));
        }
    }

    private void applyOutcome(OutboxEventEntity event, String newStatus, Instant publishedAt,
                              Integer newAttempts, Instant nextAttemptAt) {
        event.setStatus(newStatus);
        event.setLockedUntil(null);
        if (publishedAt != null) {
            event.setPublishedAt(publishedAt);
        }
        if (newAttempts != null) {
            event.setAttempts(newAttempts);
        }
        if (nextAttemptAt != null) {
            event.setNextAttemptAt(nextAttemptAt);
        }
        outboxEventRepository.save(event);
    }
}
