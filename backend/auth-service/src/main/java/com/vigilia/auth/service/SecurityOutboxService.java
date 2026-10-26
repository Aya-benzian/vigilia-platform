package com.vigilia.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityOutboxService {

    private static final Logger log = LoggerFactory.getLogger(SecurityOutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public SecurityOutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSecurityEvent(String eventType, UUID userId, String email, String ipAddress, String traceId, Map<String, Object> details) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("eventId", UUID.randomUUID().toString());
            payloadMap.put("eventType", eventType);
            payloadMap.put("userId", userId != null ? userId.toString() : null);
            payloadMap.put("email", email);
            payloadMap.put("ipAddress", ipAddress);
            payloadMap.put("occurredAt", Instant.now().toString());
            if (details != null && !details.isEmpty()) {
                payloadMap.putAll(details);
            }

            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            OutboxEventEntity outboxEvent = new OutboxEventEntity(
                    "USER",
                    userId != null ? userId.toString() : email,
                    eventType,
                    payloadJson,
                    traceId
            );

            outboxEventRepository.save(outboxEvent);
            log.info("Événement d'audit de sécurité enregistré dans l'Outbox [type={}, userId={}, traceId={}]", eventType, userId, traceId);
        } catch (JsonProcessingException e) {
            log.error("Erreur de sérialisation JSON pour l'événement Outbox {}", eventType, e);
        }
    }
}
