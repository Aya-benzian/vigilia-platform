package com.vigilia.risk.dto;

public class LiveStatsResponse {

    // Decision breakdown (count + percentage)
    private long totalEvaluated;
    private long approvedCount;
    private long pendingReviewCount;
    private long rejectedCount;
    private double approvedPct;
    private double pendingReviewPct;
    private double rejectedPct;

    // AI Risk Score distribution buckets (fraud_probability 0.0–1.0 stored as NUMERIC(6,5))
    // Bucket 0: score 0.00–0.25 → "Safe"
    // Bucket 1: score 0.26–0.50 → "Low Risk"
    // Bucket 2: score 0.51–0.75 → "Review"
    // Bucket 3: score 0.76–1.00 → "Blocked"
    private long bucket0Count;
    private double bucket0Pct;
    private long bucket1Count;
    private double bucket1Pct;
    private long bucket2Count;
    private double bucket2Pct;
    private long bucket3Count;
    private double bucket3Pct;

    // Policy enforcement log (count by decision_reason)
    private long autoApproveCount;    // decision_reason = 'POLICY_APPROVE'
    private long manualReviewCount;   // decision_reason = 'UNCERTAINTY' or 'PENDING_REVIEW'
    private long hardBlockCount;      // decision_reason = 'POLICY_REJECT' or 'HARD_LIMIT'
    private long velocityCount;       // decision_reason = 'VELOCITY_HARD_LIMIT'
    private long geoAnomalyCount;     // decision_reason = 'GEO_ANOMALY'
    private long totalRulesEvaluated; // = totalEvaluated

    // Model info
    private String modelVersion;      // most recent model_version in evaluations
    private Double avgInferenceMs;    // average inference_ms

    public long getTotalEvaluated() { return totalEvaluated; }
    public void setTotalEvaluated(long totalEvaluated) { this.totalEvaluated = totalEvaluated; }

    public long getApprovedCount() { return approvedCount; }
    public void setApprovedCount(long approvedCount) { this.approvedCount = approvedCount; }

    public long getPendingReviewCount() { return pendingReviewCount; }
    public void setPendingReviewCount(long pendingReviewCount) { this.pendingReviewCount = pendingReviewCount; }

    public long getRejectedCount() { return rejectedCount; }
    public void setRejectedCount(long rejectedCount) { this.rejectedCount = rejectedCount; }

    public double getApprovedPct() { return approvedPct; }
    public void setApprovedPct(double approvedPct) { this.approvedPct = approvedPct; }

    public double getPendingReviewPct() { return pendingReviewPct; }
    public void setPendingReviewPct(double pendingReviewPct) { this.pendingReviewPct = pendingReviewPct; }

    public double getRejectedPct() { return rejectedPct; }
    public void setRejectedPct(double rejectedPct) { this.rejectedPct = rejectedPct; }

    public long getBucket0Count() { return bucket0Count; }
    public void setBucket0Count(long bucket0Count) { this.bucket0Count = bucket0Count; }

    public double getBucket0Pct() { return bucket0Pct; }
    public void setBucket0Pct(double bucket0Pct) { this.bucket0Pct = bucket0Pct; }

    public long getBucket1Count() { return bucket1Count; }
    public void setBucket1Count(long bucket1Count) { this.bucket1Count = bucket1Count; }

    public double getBucket1Pct() { return bucket1Pct; }
    public void setBucket1Pct(double bucket1Pct) { this.bucket1Pct = bucket1Pct; }

    public long getBucket2Count() { return bucket2Count; }
    public void setBucket2Count(long bucket2Count) { this.bucket2Count = bucket2Count; }

    public double getBucket2Pct() { return bucket2Pct; }
    public void setBucket2Pct(double bucket2Pct) { this.bucket2Pct = bucket2Pct; }

    public long getBucket3Count() { return bucket3Count; }
    public void setBucket3Count(long bucket3Count) { this.bucket3Count = bucket3Count; }

    public double getBucket3Pct() { return bucket3Pct; }
    public void setBucket3Pct(double bucket3Pct) { this.bucket3Pct = bucket3Pct; }

    public long getAutoApproveCount() { return autoApproveCount; }
    public void setAutoApproveCount(long autoApproveCount) { this.autoApproveCount = autoApproveCount; }

    public long getManualReviewCount() { return manualReviewCount; }
    public void setManualReviewCount(long manualReviewCount) { this.manualReviewCount = manualReviewCount; }

    public long getHardBlockCount() { return hardBlockCount; }
    public void setHardBlockCount(long hardBlockCount) { this.hardBlockCount = hardBlockCount; }

    public long getVelocityCount() { return velocityCount; }
    public void setVelocityCount(long velocityCount) { this.velocityCount = velocityCount; }

    public long getGeoAnomalyCount() { return geoAnomalyCount; }
    public void setGeoAnomalyCount(long geoAnomalyCount) { this.geoAnomalyCount = geoAnomalyCount; }

    public long getTotalRulesEvaluated() { return totalRulesEvaluated; }
    public void setTotalRulesEvaluated(long totalRulesEvaluated) { this.totalRulesEvaluated = totalRulesEvaluated; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Double getAvgInferenceMs() { return avgInferenceMs; }
    public void setAvgInferenceMs(Double avgInferenceMs) { this.avgInferenceMs = avgInferenceMs; }
}
