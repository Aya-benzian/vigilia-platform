package com.vigilia.notification.repository;

import com.vigilia.notification.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {
}

