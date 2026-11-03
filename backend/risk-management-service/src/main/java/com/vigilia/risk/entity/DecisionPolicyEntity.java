package com.vigilia.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity for configurable risk thresholds per transaction type.
 * Schema: risk | Table: decision_policies
 * Only one active policy per transaction_type (enforced by partial unique index).
 */
@Entity
@Table(name = "decision_policies")
public class DecisionPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** fraud_probability >= rejectThreshold → REJECTED */
    @Column(name = "reject_threshold", nullable = false, precision = 6, scale = 5)
    private BigDecimal rejectThreshold;

    /** fraud_probability <= approveThreshold → APPROVED */
    @Column(name = "approve_threshold", nullable = false, precision = 6, scale = 5)
    private BigDecimal approveThreshold;

    /** uncertainty >= uncertaintyThreshold → PENDING_REVIEW */
    @Column(name = "uncertainty_threshold", nullable = false, precision = 6, scale = 5)
    private BigDecimal uncertaintyThreshold;

    @Column(name = "ml_timeout_ms", nullable = false)
    private Integer mlTimeoutMs = 1500;

    @Column(name = "max_amount_hard_limit", nullable = false, precision = 18, scale = 2)
    private BigDecimal maxAmountHardLimit = new BigDecimal("5000.00");

    @Column(name = "velocity_hard_cap_1h", nullable = false)
    private Integer velocityHardCap1h = 15;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cost_assumptions", columnDefinition = "jsonb")
    private String costAssumptions;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public DecisionPolicyEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public BigDecimal getRejectThreshold() { return rejectThreshold; }
    public void setRejectThreshold(BigDecimal rejectThreshold) { this.rejectThreshold = rejectThreshold; }

    public BigDecimal getApproveThreshold() { return approveThreshold; }
    public void setApproveThreshold(BigDecimal approveThreshold) { this.approveThreshold = approveThreshold; }

    public BigDecimal getUncertaintyThreshold() { return uncertaintyThreshold; }
    public void setUncertaintyThreshold(BigDecimal uncertaintyThreshold) { this.uncertaintyThreshold = uncertaintyThreshold; }

    public Integer getMlTimeoutMs() { return mlTimeoutMs; }
    public void setMlTimeoutMs(Integer mlTimeoutMs) { this.mlTimeoutMs = mlTimeoutMs; }

    public BigDecimal getMaxAmountHardLimit() { return maxAmountHardLimit; }
    public void setMaxAmountHardLimit(BigDecimal maxAmountHardLimit) { this.maxAmountHardLimit = maxAmountHardLimit; }

    public Integer getVelocityHardCap1h() { return velocityHardCap1h; }
    public void setVelocityHardCap1h(Integer velocityHardCap1h) { this.velocityHardCap1h = velocityHardCap1h; }

    public String getCostAssumptions() { return costAssumptions; }
    public void setCostAssumptions(String costAssumptions) { this.costAssumptions = costAssumptions; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
