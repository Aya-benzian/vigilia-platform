package com.vigilia.transaction.repository;

import com.vigilia.transaction.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(String status, Instant nextAttemptAt);

    long countByStatus(String status);

    /**
     * H4 fix: FOR UPDATE SKIP LOCKED prevents multiple relay instances from picking up the
     * same event row simultaneously, eliminating duplicate downstream delivery.
     */
    @Query(value = "SELECT * FROM transaction.outbox_events " +
                   "WHERE status = 'PENDING' AND next_attempt_at <= :now " +
                   "ORDER BY created_at ASC " +
                   "LIMIT 50 " +
                   "FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<OutboxEventEntity> findPendingReadyForUpdate(@Param("now") Instant now);
}
