package com.vigilia.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AnalystDecisionRequest {

    @NotBlank(message = "analystId is required")
    private String analystId;

    @NotNull(message = "decision is required")
    private RiskStatus decision;

    @NotBlank(message = "reason is required")
    @Size(min = 5, max = 500, message = "reason must be between 5 and 500 characters")
    private String reason;

    public AnalystDecisionRequest() {}

    public AnalystDecisionRequest(String analystId, RiskStatus decision, String reason) {
        this.analystId = analystId;
        this.decision = decision;
        this.reason = reason;
    }

    public String getAnalystId() { return analystId; }
    public void setAnalystId(String analystId) { this.analystId = analystId; }

    public RiskStatus getDecision() { return decision; }
    public void setDecision(RiskStatus decision) { this.decision = decision; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
