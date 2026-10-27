package com.vigilia.risk.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Investigation notes added by analysts during case review.
 * Schema: risk | Table: review_notes
 */
@Entity
@Table(name = "review_notes")
public class ReviewNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false)
    private ReviewCaseEntity reviewCase;

    @Column(name = "analyst_id", nullable = false)
    private UUID analystId;

    @Column(name = "note", nullable = false, columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReviewNoteEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ReviewCaseEntity getReviewCase() { return reviewCase; }
    public void setReviewCase(ReviewCaseEntity reviewCase) { this.reviewCase = reviewCase; }

    public UUID getAnalystId() { return analystId; }
    public void setAnalystId(UUID analystId) { this.analystId = analystId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
