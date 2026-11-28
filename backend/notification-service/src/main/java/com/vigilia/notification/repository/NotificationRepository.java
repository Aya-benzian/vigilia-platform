package com.vigilia.notification.repository;

import com.vigilia.notification.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<NotificationEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<NotificationEntity> findByEventId(UUID eventId);

    long countByUserIdAndReadFalse(UUID userId);

    long countByUserIdAndReadFalseAndSeverityIn(UUID userId, List<String> severities);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.read = true, n.readAt = :readAt WHERE n.userId = :userId AND n.read = false")
    int markAllAsReadByUserId(@Param("userId") UUID userId, @Param("readAt") Instant readAt);

    @Modifying
    @Query("DELETE FROM NotificationEntity n WHERE n.userId = :userId AND n.read = true")
    int deleteAllReadByUserId(@Param("userId") UUID userId);
}

