package com.vigilia.risk.repository;

import com.vigilia.risk.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Query("SELECT o FROM OutboxEventEntity o WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt ASC")
    List<OutboxEventEntity> findPendingReady(Instant now);
}
