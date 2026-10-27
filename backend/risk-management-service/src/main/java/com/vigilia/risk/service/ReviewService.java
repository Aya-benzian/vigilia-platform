package com.vigilia.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.risk.dto.AnalystDecisionRequest;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.entity.OutboxEventEntity;
import com.vigilia.risk.entity.ReviewCaseEntity;
import com.vigilia.risk.entity.ReviewDecisionEntity;
import com.vigilia.risk.entity.ReviewNoteEntity;
import com.vigilia.risk.exception.ResourceNotFoundException;
import com.vigilia.risk.repository.OutboxEventRepository;
import com.vigilia.risk.repository.ReviewCaseRepository;
import com.vigilia.risk.repository.ReviewDecisionRepository;
import com.vigilia.risk.repository.ReviewNoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for the analyst review workflow.
 *
 * Supported operations:
 * - List open review cases (with pagination)
 * - Claim a case (assign to analyst, pessimistic lock)
 * - Submit a final decision (APPROVED | REJECTED)
 * - Add investigation notes
 *
 * All mutations publish an outbox event so the Transaction Service can react.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewCaseRepository caseRepository;
    private final ReviewDecisionRepository decisionRepository;
    private final ReviewNoteRepository noteRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public ReviewService(ReviewCaseRepository caseRepository,
                         ReviewDecisionRepository decisionRepository,
                         ReviewNoteRepository noteRepository,
                         OutboxEventRepository outboxRepository,
                         ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.decisionRepository = decisionRepository;
        this.noteRepository = noteRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ReviewCaseEntity> listOpenCases(Pageable pageable) {
        return caseRepository.findByStatus("OPEN", pageable);
    }

    @Transactional(readOnly = true)
    public ReviewCaseEntity getCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Review case not found: " + caseId));
    }

    // -------------------------------------------------------------------------
    // Mutations
    // -------------------------------------------------------------------------

    /**
     * Claims a case for an analyst. Uses pessimistic locking to prevent
     * concurrent claims by two analysts.
     */
    @Transactional
    public ReviewCaseEntity claimCase(UUID caseId, UUID analystId) {
        ReviewCaseEntity reviewCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Review case not found: " + caseId));

        if ("RESOLVED".equals(reviewCase.getStatus())) {
            throw new IllegalStateException("Case " + caseId + " is already resolved");
        }
        if (analystId.equals(reviewCase.getAssignedAnalystId())) {
            log.info("[REVIEW] Case {} already claimed by analyst {}", caseId, analystId);
            return reviewCase;
        }

        reviewCase.setAssignedAnalystId(analystId);
        reviewCase.setStatus("CLAIMED");
        reviewCase.setClaimedAt(Instant.now());

        ReviewCaseEntity saved = caseRepository.save(reviewCase);
        log.info("[REVIEW] Case {} claimed by analyst {}", caseId, analystId);
        return saved;
    }

    /**
     * Submits a final analyst decision for a case.
     * Only APPROVED and REJECTED are accepted; PENDING_REVIEW is rejected.
     */
    @Transactional
    public ReviewDecisionEntity submitDecision(UUID caseId, AnalystDecisionRequest decisionRequest) {
        ReviewCaseEntity reviewCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Review case not found: " + caseId));

        if ("RESOLVED".equals(reviewCase.getStatus())) {
            throw new IllegalStateException("Case " + caseId + " is already resolved");
        }

        RiskStatus decision = decisionRequest.getDecision();
        if (decision == RiskStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Analyst decision must be APPROVED or REJECTED, not PENDING_REVIEW");
        }

        // Parse analyst ID (free-text in the current contract, convert to UUID for storage)
        UUID analystId = parseAnalystId(decisionRequest.getAnalystId());

        ReviewDecisionEntity decisionEntity = new ReviewDecisionEntity();
        decisionEntity.setReviewCase(reviewCase);
        decisionEntity.setAnalystId(analystId);
        decisionEntity.setDecision(decision.name());
        decisionEntity.setReasonCode(decisionRequest.getReason());
        decisionRepository.save(decisionEntity);

        reviewCase.setStatus("RESOLVED");
        reviewCase.setResolvedAt(Instant.now());
        caseRepository.save(reviewCase);

        publishReviewDecisionEvent(reviewCase, decisionEntity);
        log.info("[REVIEW] Case {} resolved with {} by analyst {}", caseId, decision, analystId);
        return decisionEntity;
    }

    /**
     * Adds an investigation note to a case.
     */
    @Transactional
    public ReviewNoteEntity addNote(UUID caseId, UUID analystId, String note) {
        ReviewCaseEntity reviewCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Review case not found: " + caseId));

        if ("RESOLVED".equals(reviewCase.getStatus())) {
            throw new IllegalStateException("Cannot add note to resolved case " + caseId);
        }

        ReviewNoteEntity noteEntity = new ReviewNoteEntity();
        noteEntity.setReviewCase(reviewCase);
        noteEntity.setAnalystId(analystId);
        noteEntity.setNote(note);
        ReviewNoteEntity saved = noteRepository.save(noteEntity);
        log.info("[REVIEW] Note added to case {} by analyst {}", caseId, analystId);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Publishes a review decision outbox event so the Transaction Service can react.
     *
     * IMPORTANT: Serialization failure throws IllegalStateException (RuntimeException)
     * to trigger @Transactional rollback. The decision must never be committed
     * without its outbox event.
     *
     * @throws IllegalStateException if the payload cannot be serialized
     */
    private void publishReviewDecisionEvent(ReviewCaseEntity reviewCase, ReviewDecisionEntity decision) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("caseId", reviewCase.getId().toString());
        payload.put("transactionId", reviewCase.getTransactionId().toString());
        payload.put("decision", decision.getDecision());
        payload.put("reasonCode", decision.getReasonCode());
        payload.put("analystId", decision.getAnalystId().toString());
        payload.put("resolvedAt", decision.getCreatedAt().toString());

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "[REVIEW] Cannot serialize outbox event for caseId=" + reviewCase.getId()
                    + "; rolling back transaction to preserve atomicity.", ex);
        }

        OutboxEventEntity outboxEvent = new OutboxEventEntity(
                "REVIEW_DECISION",
                reviewCase.getTransactionId().toString(),
                "REVIEW_DECISION_COMPLETED",
                jsonPayload,
                MDC.get("correlationId")
        );
        outboxRepository.save(outboxEvent);
    }

    private UUID parseAnalystId(String analystId) {
        try {
            return UUID.fromString(analystId);
        } catch (IllegalArgumentException ex) {
            // Analyst IDs may be strings (e.g., "analyst-01"); generate deterministic UUID
            return UUID.nameUUIDFromBytes(analystId.getBytes());
        }
    }
}
