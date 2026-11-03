package com.vigilia.transaction.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative response returned by Risk Management Service.
 */
public class RiskEvaluationResponse {

    private UUID evaluationId;
    private UUID transactionId;
    private RiskStatus decision;
    private Double fraudProbability;
    private Double uncertainty;
    private String decisionReason;
    private Instant evaluatedAt;

    public RiskEvaluationResponse() {
    }

    public RiskEvaluationResponse(UUID evaluationId, UUID transactionId, RiskStatus decision, Double fraudProbability, String decisionReason, Instant evaluatedAt) {
        this.evaluationId = evaluationId;
        this.transactionId = transactionId;
        this.decision = decision;
        this.fraudProbability = fraudProbability;
        this.decisionReason = decisionReason;
        this.evaluatedAt = evaluatedAt;
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public void setEvaluationId(UUID evaluationId) {
        this.evaluationId = evaluationId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public RiskStatus getDecision() {
        return decision;
    }

    public void setDecision(RiskStatus decision) {
        this.decision = decision;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public void setFraudProbability(Double fraudProbability) {
        this.fraudProbability = fraudProbability;
    }

    public Double getUncertainty() {
        return uncertainty;
    }

    public void setUncertainty(Double uncertainty) {
        this.uncertainty = uncertainty;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(Instant evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }
}
