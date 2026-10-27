package com.vigilia.risk.dto;

import com.vigilia.risk.entity.DecisionPolicyEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO for consulting decision policies via GET /api/v1/risk/policies/active.
 * Only public policy thresholds and configuration parameters are exposed.
 * Internal fields such as createdBy and costAssumptions are intentionally omitted.
 */
public class DecisionPolicyResponse {

    private UUID id;
    private String transactionType;
    private Integer version;
    private BigDecimal rejectThreshold;
    private BigDecimal approveThreshold;
    private BigDecimal uncertaintyThreshold;
    private Integer mlTimeoutMs;
    private BigDecimal maxAmountHardLimit;
    private Integer velocityHardCap1h;
    private Boolean isActive;
    private Instant createdAt;

    public DecisionPolicyResponse() {}

    public DecisionPolicyResponse(UUID id,
                                  String transactionType,
                                  Integer version,
                                  BigDecimal rejectThreshold,
                                  BigDecimal approveThreshold,
                                  BigDecimal uncertaintyThreshold,
                                  Integer mlTimeoutMs,
                                  BigDecimal maxAmountHardLimit,
                                  Integer velocityHardCap1h,
                                  Boolean isActive,
                                  Instant createdAt) {
        this.id = id;
        this.transactionType = transactionType;
        this.version = version;
        this.rejectThreshold = rejectThreshold;
        this.approveThreshold = approveThreshold;
        this.uncertaintyThreshold = uncertaintyThreshold;
        this.mlTimeoutMs = mlTimeoutMs;
        this.maxAmountHardLimit = maxAmountHardLimit;
        this.velocityHardCap1h = velocityHardCap1h;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    public static DecisionPolicyResponse fromEntity(DecisionPolicyEntity entity) {
        if (entity == null) {
            return null;
        }
        return new DecisionPolicyResponse(
                entity.getId(),
                entity.getTransactionType(),
                entity.getVersion(),
                entity.getRejectThreshold(),
                entity.getApproveThreshold(),
                entity.getUncertaintyThreshold(),
                entity.getMlTimeoutMs(),
                entity.getMaxAmountHardLimit(),
                entity.getVelocityHardCap1h(),
                entity.getIsActive(),
                entity.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public BigDecimal getRejectThreshold() {
        return rejectThreshold;
    }

    public void setRejectThreshold(BigDecimal rejectThreshold) {
        this.rejectThreshold = rejectThreshold;
    }

    public BigDecimal getApproveThreshold() {
        return approveThreshold;
    }

    public void setApproveThreshold(BigDecimal approveThreshold) {
        this.approveThreshold = approveThreshold;
    }

    public BigDecimal getUncertaintyThreshold() {
        return uncertaintyThreshold;
    }

    public void setUncertaintyThreshold(BigDecimal uncertaintyThreshold) {
        this.uncertaintyThreshold = uncertaintyThreshold;
    }

    public Integer getMlTimeoutMs() {
        return mlTimeoutMs;
    }

    public void setMlTimeoutMs(Integer mlTimeoutMs) {
        this.mlTimeoutMs = mlTimeoutMs;
    }

    public BigDecimal getMaxAmountHardLimit() {
        return maxAmountHardLimit;
    }

    public void setMaxAmountHardLimit(BigDecimal maxAmountHardLimit) {
        this.maxAmountHardLimit = maxAmountHardLimit;
    }

    public Integer getVelocityHardCap1h() {
        return velocityHardCap1h;
    }

    public void setVelocityHardCap1h(Integer velocityHardCap1h) {
        this.velocityHardCap1h = velocityHardCap1h;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
