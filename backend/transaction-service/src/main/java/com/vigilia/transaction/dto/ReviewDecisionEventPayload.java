package com.vigilia.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewDecisionEventPayload {

    private UUID eventId;

    @NotNull(message = "transactionId est obligatoire")
    private UUID transactionId;

    private UUID caseId;

    @NotNull(message = "decision est obligatoire")
    private RiskStatus decision;

    private String reasonCode;

    private String analystId;

    private String resolvedAt;

    private String traceId;

    public ReviewDecisionEventPayload() {
    }

    public ReviewDecisionEventPayload(UUID eventId, UUID transactionId, RiskStatus decision, String reasonCode, String analystId) {
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.analystId = analystId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public RiskStatus getDecision() {
        return decision;
    }

    public void setDecision(RiskStatus decision) {
        this.decision = decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getAnalystId() {
        return analystId;
    }

    public void setAnalystId(String analystId) {
        this.analystId = analystId;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
