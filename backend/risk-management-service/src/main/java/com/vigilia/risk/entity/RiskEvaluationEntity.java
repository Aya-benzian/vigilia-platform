package com.vigilia.risk.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only risk evaluation record for each transaction.
 * Schema: risk | Table: risk_evaluations
 * One record per transaction_id (UNIQUE constraint).
 */
@Entity
@Table(name = "risk_evaluations")
public class RiskEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @Column(name = "feature_spec_version", length = 50)
    private String featureSpecVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "jsonb")
    private String features;

    @Column(name = "prediction_label", length = 50)
    private String predictionLabel;

    @Column(name = "fraud_probability", precision = 6, scale = 5)
    private BigDecimal fraudProbability;

    @Column(name = "uncertainty", precision = 6, scale = 5)
    private BigDecimal uncertainty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shap_values", columnDefinition = "jsonb")
    private String shapValues;

    @Column(name = "inference_ms")
    private Integer inferenceMs;

    /** ML call status: SUCCESS, TIMEOUT, ERROR, COLD_START */
    @Column(name = "ml_status", nullable = false, length = 20)
    private String mlStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    private DecisionPolicyEntity policy;

    /** Final decision: APPROVED, REJECTED, PENDING_REVIEW */
    @Column(name = "decision", nullable = false, length = 30)
    private String decision;

    /** Reason code: POLICY_APPROVE, POLICY_REJECT, UNCERTAINTY, HARD_LIMIT, ML_FALLBACK, etc. */
    @Column(name = "decision_reason", nullable = false, length = 50)
    private String decisionReason;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public RiskEvaluationEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public String getFeatureSpecVersion() { return featureSpecVersion; }
    public void setFeatureSpecVersion(String featureSpecVersion) { this.featureSpecVersion = featureSpecVersion; }

    public String getFeatures() { return features; }
    public void setFeatures(String features) { this.features = features; }

    public String getPredictionLabel() { return predictionLabel; }
    public void setPredictionLabel(String predictionLabel) { this.predictionLabel = predictionLabel; }

    public BigDecimal getFraudProbability() { return fraudProbability; }
    public void setFraudProbability(BigDecimal fraudProbability) { this.fraudProbability = fraudProbability; }

    public BigDecimal getUncertainty() { return uncertainty; }
    public void setUncertainty(BigDecimal uncertainty) { this.uncertainty = uncertainty; }

    public String getShapValues() { return shapValues; }
    public void setShapValues(String shapValues) { this.shapValues = shapValues; }

    public Integer getInferenceMs() { return inferenceMs; }
    public void setInferenceMs(Integer inferenceMs) { this.inferenceMs = inferenceMs; }

    public String getMlStatus() { return mlStatus; }
    public void setMlStatus(String mlStatus) { this.mlStatus = mlStatus; }

    public DecisionPolicyEntity getPolicy() { return policy; }
    public void setPolicy(DecisionPolicyEntity policy) { this.policy = policy; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
