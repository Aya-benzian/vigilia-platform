package com.vigilia.risk.service;

import com.vigilia.risk.entity.CustomerActivityEntity;
import com.vigilia.risk.repository.CustomerActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Service for reading and writing customer activity projections.
 * Used to compute rolling velocity features for fraud detection.
 *
 * Data is written here after a final settlement event arrives from the Transaction Service.
 */
@Service
public class CustomerActivityService {

    private static final Logger log = LoggerFactory.getLogger(CustomerActivityService.class);

    private final CustomerActivityRepository activityRepository;

    public CustomerActivityService(CustomerActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    /**
     * Returns the number of transactions completed by this customer in the last hour.
     * Used as the velocity_hard_cap_1h feature.
     */
    @Transactional(readOnly = true)
    public long countTransactionsInLastHour(UUID customerId) {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        return activityRepository.countByCustomerIdSince(customerId, since);
    }

    /**
     * Returns the total amount transacted by this customer in the last hour.
     */
    @Transactional(readOnly = true)
    public BigDecimal sumAmountInLastHour(UUID customerId) {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        return activityRepository.sumAmountByCustomerIdSince(customerId, since);
    }

    /**
     * Records a completed transaction as customer activity (idempotent).
     * Called after the final SETTLEMENT or FAILED event from the Transaction Service.
     */
    @Transactional
    public void recordActivity(UUID customerId,
                               UUID transactionId,
                               String transactionType,
                               BigDecimal amount,
                               String counterpartyId,
                               String category,
                               Double lat,
                               Double lon,
                               String finalStatus,
                               Instant occurredAt) {
        if (activityRepository.existsByTransactionId(transactionId)) {
            log.debug("[ACTIVITY] Skipping duplicate activity record for transactionId={}", transactionId);
            return;
        }

        CustomerActivityEntity entity = new CustomerActivityEntity();
        entity.setCustomerId(customerId);
        entity.setTransactionId(transactionId);
        entity.setTransactionType(transactionType);
        entity.setAmount(amount);
        entity.setCounterpartyId(counterpartyId);
        entity.setCategory(category);
        entity.setLat(lat != null ? BigDecimal.valueOf(lat) : null);
        entity.setLon(lon != null ? BigDecimal.valueOf(lon) : null);
        entity.setFinalStatus(finalStatus);
        entity.setOccurredAt(occurredAt != null ? occurredAt : Instant.now());

        activityRepository.save(entity);
        log.debug("[ACTIVITY] Recorded transactionId={} for customerId={}", transactionId, customerId);
    }
}
