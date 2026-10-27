package com.vigilia.risk.repository;

import com.vigilia.risk.entity.ReviewNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewNoteRepository extends JpaRepository<ReviewNoteEntity, UUID> {

    List<ReviewNoteEntity> findByReviewCaseIdOrderByCreatedAtAsc(UUID caseId);
}
