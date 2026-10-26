package com.vigilia.risk.repository;

import com.vigilia.risk.entity.ReviewDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewDecisionRepository extends JpaRepository<ReviewDecisionEntity, UUID> {

    Optional<ReviewDecisionEntity> findByReviewCaseId(UUID caseId);
}
