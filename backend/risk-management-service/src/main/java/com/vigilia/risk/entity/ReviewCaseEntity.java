package com.vigilia.risk.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Human review work item created when a transaction lands in PENDING_REVIEW.
 * Schema: risk | Table: review_cases
 * Uses optimistic locking via 'version' column.
 */
@Entity
@Table(name = "review_cases")
public class ReviewCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "risk_evaluation_id", nullable = false)
    private RiskEvaluationEntity riskEvaluation;

    /** OPEN, CLAIMED, RESOLVED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    /** LOW, MEDIUM, HIGH, CRITICAL */
    @Column(name = "priority", nullable = false, length = 20)
    private String priority = "MEDIUM";

    @Column(name = "assigned_analyst_id")
    private UUID assignedAnalystId;

    @Column(name = "sla_due_at", nullable = false)
    private Instant slaDueAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public ReviewCaseEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public RiskEvaluationEntity getRiskEvaluation() { return riskEvaluation; }
    public void setRiskEvaluation(RiskEvaluationEntity riskEvaluation) { this.riskEvaluation = riskEvaluation; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public UUID getAssignedAnalystId() { return assignedAnalystId; }
    public void setAssignedAnalystId(UUID assignedAnalystId) { this.assignedAnalystId = assignedAnalystId; }

    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant slaDueAt) { this.slaDueAt = slaDueAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
