package com.vigilia.risk.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only analyst decision for a review case.
 * Schema: risk | Table: review_decisions
 * One decision per case_id (UNIQUE).
 */
@Entity
@Table(name = "review_decisions")
public class ReviewDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, unique = true)
    private ReviewCaseEntity reviewCase;

    @Column(name = "analyst_id", nullable = false)
    private UUID analystId;

    /** APPROVED or REJECTED */
    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReviewDecisionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ReviewCaseEntity getReviewCase() { return reviewCase; }
    public void setReviewCase(ReviewCaseEntity reviewCase) { this.reviewCase = reviewCase; }

    public UUID getAnalystId() { return analystId; }
    public void setAnalystId(UUID analystId) { this.analystId = analystId; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
