package com.vigilia.customer.repository;

import com.vigilia.customer.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatusAndNextAttemptAtBeforeOrderByCreatedAtAsc(String status, Instant nextAttemptAt);
}
