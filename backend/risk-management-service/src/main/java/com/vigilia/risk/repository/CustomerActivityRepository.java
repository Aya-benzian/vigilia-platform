package com.vigilia.risk.repository;

import com.vigilia.risk.entity.CustomerActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Repository
public interface CustomerActivityRepository extends JpaRepository<CustomerActivityEntity, UUID> {

    /** Count of transactions for a customer in the last N hours (velocity check). */
    @Query("SELECT COUNT(ca) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId AND ca.occurredAt >= :since")
    long countByCustomerIdSince(@Param("customerId") UUID customerId,
                                @Param("since") Instant since);

    /** Total amount transacted by a customer in the last N hours. */
    @Query("SELECT COALESCE(SUM(ca.amount), 0) FROM CustomerActivityEntity ca " +
           "WHERE ca.customerId = :customerId AND ca.occurredAt >= :since")
    BigDecimal sumAmountByCustomerIdSince(@Param("customerId") UUID customerId,
                                         @Param("since") Instant since);

    boolean existsByTransactionId(UUID transactionId);
}
