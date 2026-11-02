package com.vigilia.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.entity.OutboxEventEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for publishing outbox events within the Transactional Outbox pattern.
 * All publish operations run under MANDATORY propagation to guarantee atomicity with financial operations.
 */
@Service
public class TransactionOutboxService {

    private static final Logger log = LoggerFactory.getLogger(TransactionOutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public TransactionOutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes an outbox event when a transaction evaluation is applied directly (APPROVED or REJECTED).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTransactionCompleted(TransactionEntity transaction, RiskStatus decision, String traceId) {
        String eventType = (decision == RiskStatus.APPROVED) ? "TRANSACTION_COMPLETED" : "TRANSACTION_REJECTED";
        String resolvedTraceId = resolveTraceId(traceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("transactionId", transaction.getId().toString());
        payload.put("customerId", transaction.getCustomerId().toString());
        payload.put("amount", transaction.getAmount());
        payload.put("currency", transaction.getCurrency());
        payload.put("transactionType", transaction.getTransactionType());
        payload.put("status", transaction.getStatus());
        payload.put("finalizedAt", transaction.getFinalizedAt() != null ? transaction.getFinalizedAt().toString() : Instant.now().toString());
        payload.put("traceId", resolvedTraceId);

        saveOutboxEvent(transaction.getId().toString(), eventType, payload, resolvedTraceId);
    }

    /**
     * Publishes an outbox event when an analyst manually resolves a PENDING_REVIEW case.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTransactionResolved(TransactionEntity transaction, RiskStatus decision, String reason, String analystId, String traceId) {
        String eventType = "TRANSACTION_RESOLVED";
        String resolvedTraceId = resolveTraceId(traceId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("transactionId", transaction.getId().toString());
        payload.put("customerId", transaction.getCustomerId().toString());
        payload.put("amount", transaction.getAmount());
        payload.put("currency", transaction.getCurrency());
        payload.put("transactionType", transaction.getTransactionType());
        payload.put("decision", decision.name());
        payload.put("status", transaction.getStatus());
        payload.put("reason", reason);
        payload.put("analystId", analystId);
        payload.put("finalizedAt", transaction.getFinalizedAt() != null ? transaction.getFinalizedAt().toString() : Instant.now().toString());
        payload.put("traceId", resolvedTraceId);

        saveOutboxEvent(transaction.getId().toString(), eventType, payload, resolvedTraceId);
    }

    private void saveOutboxEvent(String aggregateId, String eventType, Map<String, Object> payload, String traceId) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[OUTBOX] Erreur de sérialisation JSON pour l'événement {} [aggregateId={}]: {}",
                    eventType, aggregateId, e.getMessage());
            throw new IllegalStateException("Erreur de sérialisation de l'événement Outbox", e);
        }

        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                "TRANSACTION",
                aggregateId,
                eventType,
                jsonPayload,
                traceId
        );

        outboxEventRepository.save(outboxEvent);
        log.info("[OUTBOX] Événement Outbox enregistré avec succès [type={}, aggregateId={}, traceId={}]",
                eventType, aggregateId, traceId);
    }

    private String resolveTraceId(String explicitTraceId) {
        if (explicitTraceId != null && !explicitTraceId.isBlank()) {
            return explicitTraceId;
        }
        String mdcCorrelationId = MDC.get("correlationId");
        if (mdcCorrelationId != null && !mdcCorrelationId.isBlank()) {
            return mdcCorrelationId;
        }
        return UUID.randomUUID().toString();
    }
}
