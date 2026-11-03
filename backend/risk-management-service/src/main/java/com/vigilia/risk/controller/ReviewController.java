package com.vigilia.risk.controller;

import com.vigilia.risk.dto.AnalystDecisionRequest;
import com.vigilia.risk.dto.ApiResponse;
import com.vigilia.risk.entity.ReviewCaseEntity;
import com.vigilia.risk.entity.ReviewDecisionEntity;
import com.vigilia.risk.entity.ReviewNoteEntity;
import com.vigilia.risk.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for analyst review workflow.
 * Base path: /api/v1/risk/reviews
 *
 * Routes aligned with contracts/api/risk-openapi.yaml:
 *
 * GET  /api/v1/risk/reviews/pending              → list cases in OPEN / PENDING_REVIEW status (contract: listPendingReviews)
 * GET  /api/v1/risk/reviews/{caseId}             → get a specific case
 * POST /api/v1/risk/reviews/{caseId}/claim       → claim a case (assign to analyst)
 * POST /api/v1/risk/reviews/{caseId}/decision    → submit final APPROVED/REJECTED decision (contract: submitAnalystDecision)
 * POST /api/v1/risk/reviews/{caseId}/notes       → add an investigation note
 *
 * Note: the contract uses /{id}/decision with path param named 'id'.
 * Internally we use 'caseId' for clarity; the wire format is identical.
 */
@RestController
@RequestMapping("/api/v1/risk/reviews")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * Lists cases pending human review (status = OPEN).
     * Route aligned with the contract: GET /api/v1/risk/reviews/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<Page<ReviewCaseEntity>>> listPendingReviews(
            @PageableDefault(size = 20, sort = "openedAt") Pageable pageable) {
        Page<ReviewCaseEntity> page = reviewService.listOpenCases(pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    /**
     * Returns a specific review case by its UUID.
     */
    @GetMapping("/{caseId}")
    public ResponseEntity<ApiResponse<ReviewCaseEntity>> getCase(@PathVariable UUID caseId) {
        ReviewCaseEntity reviewCase = reviewService.getCase(caseId);
        return ResponseEntity.ok(ApiResponse.of(reviewCase));
    }

    /**
     * Claims a case for an analyst (pessimistic lock prevents double-claim).
     */
    @PostMapping("/{caseId}/claim")
    public ResponseEntity<ApiResponse<ReviewCaseEntity>> claimCase(
            @PathVariable UUID caseId,
            @RequestBody @Valid ClaimRequest claimRequest) {
        ReviewCaseEntity updated = reviewService.claimCase(caseId, claimRequest.getAnalystId());
        return ResponseEntity.ok(ApiResponse.of(updated));
    }

    /**
     * Submits a final analyst decision (APPROVED or REJECTED).
     * Only APPROVED and REJECTED are accepted; PENDING_REVIEW is rejected with 400.
     */
    @PostMapping("/{caseId}/decision")
    public ResponseEntity<ApiResponse<ReviewDecisionEntity>> submitDecision(
            @PathVariable UUID caseId,
            @RequestBody @Valid AnalystDecisionRequest decisionRequest) {
        ReviewDecisionEntity decision = reviewService.submitDecision(caseId, decisionRequest);
        return ResponseEntity.ok(ApiResponse.of(decision));
    }

    /**
     * Adds an investigation note to an open case.
     */
    @PostMapping("/{caseId}/notes")
    public ResponseEntity<ApiResponse<ReviewNoteEntity>> addNote(
            @PathVariable UUID caseId,
            @RequestBody @Valid NoteRequest noteRequest) {
        ReviewNoteEntity note = reviewService.addNote(caseId, noteRequest.getAnalystId(), noteRequest.getNote());
        return ResponseEntity.ok(ApiResponse.of(note));
    }

    // -------------------------------------------------------------------------
    // Inner request DTOs (kept here to avoid proliferating trivial files)
    // -------------------------------------------------------------------------

    public static class ClaimRequest {
        @NotNull(message = "analystId is required")
        private UUID analystId;

        public ClaimRequest() {}
        public UUID getAnalystId() { return analystId; }
        public void setAnalystId(UUID analystId) { this.analystId = analystId; }
    }

    public static class NoteRequest {
        @NotNull(message = "analystId is required")
        private UUID analystId;

        @NotBlank(message = "note is required")
        private String note;

        public NoteRequest() {}
        public UUID getAnalystId() { return analystId; }
        public void setAnalystId(UUID analystId) { this.analystId = analystId; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
