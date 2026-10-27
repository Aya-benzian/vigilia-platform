package com.vigilia.customer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.customer.entity.CustomerEntity;
import com.vigilia.customer.entity.OutboxEventEntity;
import com.vigilia.customer.repository.OutboxEventRepository;
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
public class CustomerOutboxService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public CustomerOutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCustomerCreated(CustomerEntity customer, String traceId) {
        publishEvent("CUSTOMER_CREATED", customer, traceId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCustomerUpdated(CustomerEntity customer, String traceId) {
        publishEvent("CUSTOMER_UPDATED", customer, traceId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishEvent(String eventType, CustomerEntity customer, String traceId, Map<String, Object> extraData) {
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("eventId", UUID.randomUUID().toString());
            payloadMap.put("eventType", eventType);
            payloadMap.put("customerId", customer.getId().toString());
            payloadMap.put("userId", customer.getUserId().toString());
            payloadMap.put("accountNumber", customer.getAccountNumber());
            payloadMap.put("fullName", customer.getFullName());
            payloadMap.put("homeCity", customer.getHomeCity());
            payloadMap.put("homeCountry", customer.getHomeCountry());
            payloadMap.put("cityPopulation", customer.getCityPopulation());
            payloadMap.put("occurredAt", Instant.now().toString());

            if (extraData != null && !extraData.isEmpty()) {
                payloadMap.putAll(extraData);
            }

            String payloadJson = objectMapper.writeValueAsString(payloadMap);

            OutboxEventEntity outboxEvent = new OutboxEventEntity(
                    "CUSTOMER",
                    customer.getId().toString(),
                    eventType,
                    payloadJson,
                    traceId
            );

            outboxEventRepository.save(outboxEvent);
            log.info("Événement Outbox enregistré [type={}, customerId={}, traceId={}]",
                    eventType, customer.getId(), traceId);
        } catch (JsonProcessingException e) {
            log.error("Erreur de sérialisation JSON pour l'événement Outbox {}", eventType, e);
            throw new RuntimeException("Erreur de sérialisation de l'événement Outbox", e);
        }
    }
}
