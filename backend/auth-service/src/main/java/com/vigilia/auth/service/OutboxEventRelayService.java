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
    private RestClient notificationRestClient;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureNotificationClient(
            RestClient.Builder builder,
            @Value("${notification.service.url:http://localhost:8085}") String notificationServiceUrl) {
        if (builder != null && notificationServiceUrl != null && !notificationServiceUrl.isBlank()) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(5));
            this.notificationRestClient = builder
                    .baseUrl(notificationServiceUrl.trim())
                    .requestFactory(factory)
                    .build();
        }
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
        String eventType = event.getEventType();
        if (isSecurityNotificationEvent(eventType) && notificationRestClient != null) {
            relaySecurityNotification(event);
            return;
        }
        if (!"USER_REGISTERED".equals(eventType)) {
            log.debug("Type d'événement ignoré par le relais Customer [type={}, id={}]",
                    eventType, event.getId());
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

    private boolean isSecurityNotificationEvent(String eventType) {
        return "LOGIN_SUCCEEDED".equals(eventType)
                || "ACCOUNT_LOCKED".equals(eventType)
                || "PASSWORD_CHANGED".equals(eventType)
                || "LOGIN_FAILED".equals(eventType);
    }

    private void relaySecurityNotification(OutboxEventEntity event) {
        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
        } catch (Exception ex) {
            event.setStatus("FAILED");
            outboxEventRepository.save(event);
            return;
        }

        String userIdStr = payloadNode.path("userId").asText(null);
        if (userIdStr == null || userIdStr.isBlank() || "null".equalsIgnoreCase(userIdStr)) {
            // Anonymous failed login (unknown user) — nothing to show in a customer's inbox
            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
            return;
        }

        String eventType = event.getEventType();
        String ipAddress = payloadNode.path("ipAddress").asText("unknown IP");
        String title;
        String message;
        String mappedType;

        switch (eventType) {
            case "ACCOUNT_LOCKED" -> {
                mappedType = "ACCOUNT_LOCKED";
                title = "Account Temporarily Locked";
                message = "Multiple failed sign-in attempts were detected from " + ipAddress + ". Your account has been temporarily locked for your protection.";
            }
            case "PASSWORD_CHANGED" -> {
                mappedType = "PASSWORD_CHANGED";
                title = "Password Changed";
                message = "Your Vigilia account password was recently updated from " + ipAddress + ".";
            }
            case "LOGIN_FAILED" -> {
                mappedType = "SECURITY_ALERT";
                title = "Failed Sign-In Attempt";
                message = "A failed sign-in attempt was detected on your account from " + ipAddress + ".";
            }
            default -> {
                mappedType = "NEW_LOGIN_DETECTED";
                title = "New Sign-In Detected";
                message = "A new sign-in to your Vigilia account was recorded from " + ipAddress + ".";
            }
        }

        ObjectNode notifBody = objectMapper.createObjectNode();
        notifBody.put("eventId", payloadNode.path("eventId").asText(UUID.randomUUID().toString()));
        notifBody.put("eventType", mappedType);
        notifBody.put("timestamp", payloadNode.path("occurredAt").asText(Instant.now().toString()));
        notifBody.put("customerId", userIdStr);
        notifBody.put("channel", "IN_APP");
        notifBody.put("title", title);
        notifBody.put("message", message);
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("ipAddress", ipAddress);
        meta.put("sourceEvent", eventType);
        String email = payloadNode.path("email").asText("");
        if (!email.isBlank()) {
            meta.put("email", email);
        }
        notifBody.set("metadata", meta);

        String traceId = event.getTraceId() != null && !event.getTraceId().isBlank()
                ? event.getTraceId()
                : UUID.randomUUID().toString();

        try {
            notificationRestClient.post()
                    .uri("/api/v1/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Correlation-Id", traceId)
                    .body(notifBody)
                    .retrieve()
                    .toBodilessEntity();

            event.setStatus("PUBLISHED");
            event.setPublishedAt(Instant.now());
            outboxEventRepository.save(event);
        } catch (Exception ex) {
            int attempts = (event.getAttempts() != null ? event.getAttempts() : 0) + 1;
            event.setAttempts(attempts);
            if (attempts >= maxAttempts) {
                event.setStatus("FAILED");
            } else {
                long backoffSeconds = 5L * (1L << Math.min(attempts - 1, 5));
                event.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds));
            }
            outboxEventRepository.save(event);
        }
    }
}
