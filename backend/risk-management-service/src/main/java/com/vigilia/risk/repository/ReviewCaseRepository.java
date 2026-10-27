package com.vigilia.risk.repository;

import com.vigilia.risk.entity.ReviewCaseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewCaseRepository extends JpaRepository<ReviewCaseEntity, UUID> {

    Optional<ReviewCaseEntity> findByTransactionId(UUID transactionId);

    /** Pessimistic lock for analyst claim to avoid concurrent assignment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rc FROM ReviewCaseEntity rc WHERE rc.id = :id")
    Optional<ReviewCaseEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<ReviewCaseEntity> findByStatus(String status, Pageable pageable);

    Page<ReviewCaseEntity> findByStatusAndAssignedAnalystId(String status, UUID analystId, Pageable pageable);
}
