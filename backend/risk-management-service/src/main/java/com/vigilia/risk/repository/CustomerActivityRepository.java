package com.vigilia.risk.repository;

import com.vigilia.risk.entity.CustomerActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the customer_activity ledger.
 *
 * All queries are strictly point-in-time: features are calculated using events
 * that occurred BEFORE the current transaction timestamp to avoid future leakage.
 */
@Repository
public interface CustomerActivityRepository extends JpaRepository<CustomerActivityEntity, UUID> {

    // -------------------------------------------------------------------------
    // Velocity / count queries (used by RiskEvaluationService hard checks)
    // -------------------------------------------------------------------------

    /** Count of ALL transaction types for a customer since a given timestamp. */
    @Query("SELECT COUNT(ca) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId AND ca.occurredAt >= :since")
    long countByCustomerIdSince(@Param("customerId") UUID customerId,
                                @Param("since") Instant since);

    /** Total amount for all transactions by a customer since a given timestamp. */
    @Query("SELECT COALESCE(SUM(ca.amount), 0) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId AND ca.occurredAt >= :since")
    BigDecimal sumAmountByCustomerIdSince(@Param("customerId") UUID customerId,
                                          @Param("since") Instant since);

    boolean existsByTransactionId(UUID transactionId);

    // -------------------------------------------------------------------------
    // Purchase feature queries (29 features — purchase-v2)
    // -------------------------------------------------------------------------

    /** All PURCHASE activity rows for a customer before a given timestamp (point-in-time). */
    @Query("SELECT ca FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt < :before " +
           "ORDER BY ca.occurredAt ASC")
    List<CustomerActivityEntity> findPurchaseHistoryBefore(@Param("customerId") UUID customerId,
                                                           @Param("before") Instant before);

    /** Average purchase amount for a customer before a given timestamp. */
    @Query("SELECT COALESCE(AVG(ca.amount), 0) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt < :before")
    BigDecimal avgPurchaseAmountBefore(@Param("customerId") UUID customerId,
                                       @Param("before") Instant before);

    /** Max purchase amount for a customer before a given timestamp. */
    @Query("SELECT COALESCE(MAX(ca.amount), 0) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt < :before")
    BigDecimal maxPurchaseAmountBefore(@Param("customerId") UUID customerId,
                                       @Param("before") Instant before);

    /** Count of PURCHASE transactions in a time window [since, before). */
    @Query("SELECT COUNT(ca) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt >= :since AND ca.occurredAt < :before")
    long countPurchaseInWindow(@Param("customerId") UUID customerId,
                               @Param("since") Instant since,
                               @Param("before") Instant before);

    /** Sum of PURCHASE amounts in a time window [since, before). */
    @Query("SELECT COALESCE(SUM(ca.amount), 0) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt >= :since AND ca.occurredAt < :before")
    BigDecimal sumPurchaseAmountInWindow(@Param("customerId") UUID customerId,
                                         @Param("since") Instant since,
                                         @Param("before") Instant before);

    /** Most recent PURCHASE activity for a customer before a given timestamp. */
    @Query("SELECT ca FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'PURCHASE' " +
           "AND ca.occurredAt < :before " +
           "ORDER BY ca.occurredAt DESC")
    List<CustomerActivityEntity> findMostRecentPurchaseBefore(@Param("customerId") UUID customerId,
                                                              @Param("before") Instant before);

    // -------------------------------------------------------------------------
    // Transfer OUT feature queries (20 features — transfer-v2, sender perspective)
    // -------------------------------------------------------------------------

    /** All TRANSFER activity rows sent by a customer before a given timestamp. */
    @Query("SELECT ca FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'TRANSFER' " +
           "AND ca.occurredAt < :before " +
           "ORDER BY ca.occurredAt ASC")
    List<CustomerActivityEntity> findTransferHistoryBefore(@Param("customerId") UUID customerId,
                                                           @Param("before") Instant before);

    /** Count of TRANSFER transactions sent in a time window [since, before). */
    @Query("SELECT COUNT(ca) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId " +
           "AND ca.transactionType = 'TRANSFER' " +
           "AND ca.occurredAt >= :since AND ca.occurredAt < :before")
    long countTransferSentInWindow(@Param("customerId") UUID customerId,
                                   @Param("since") Instant since,
                                   @Param("before") Instant before);

    // -------------------------------------------------------------------------
    // Recipient inbound queries (Transfer mule-pattern detection)
    // -------------------------------------------------------------------------

    /**
     * Count of inbound transfers received by a recipient (via counterparty_id linking)
     * in a time window. Used to compute recipient_inbound_count_24h.
     * Note: counterpartyId stores the recipient's customerId string for TRANSFER records.
     */
    @Query("SELECT COUNT(ca) FROM CustomerActivityEntity ca " +
           "WHERE ca.counterpartyId = :recipientId " +
           "AND ca.transactionType = 'TRANSFER' " +
           "AND ca.occurredAt >= :since AND ca.occurredAt < :before")
    long countInboundToRecipientInWindow(@Param("recipientId") String recipientId,
                                          @Param("since") Instant since,
                                          @Param("before") Instant before);

    /**
     * Distinct number of senders who sent to a given recipient in a time window.
     * Used to compute recipient_unique_senders_24h (money-mule detection).
     */
    @Query("SELECT COUNT(DISTINCT ca.customerId) FROM CustomerActivityEntity ca " +
           "WHERE ca.counterpartyId = :recipientId " +
           "AND ca.transactionType = 'TRANSFER' " +
           "AND ca.occurredAt >= :since AND ca.occurredAt < :before")
    long countDistinctSendersToRecipientInWindow(@Param("recipientId") String recipientId,
                                                  @Param("since") Instant since,
                                                  @Param("before") Instant before);
}
