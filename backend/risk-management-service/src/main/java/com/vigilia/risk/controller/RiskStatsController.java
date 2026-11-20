package com.vigilia.risk.controller;

import com.vigilia.risk.dto.ApiResponse;
import com.vigilia.risk.dto.LiveStatsResponse;
import com.vigilia.risk.repository.RiskEvaluationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk/stats")
public class RiskStatsController {

    private final RiskEvaluationRepository repo;

    public RiskStatsController(RiskEvaluationRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LiveStatsResponse>> getLiveStats() {
        LiveStatsResponse stats = new LiveStatsResponse();

        long total = repo.count();
        long approved = repo.countByDecision("APPROVED");
        long pendingReview = repo.countByDecision("PENDING_REVIEW");
        long rejected = repo.countByDecision("REJECTED");

        stats.setTotalEvaluated(total);
        stats.setApprovedCount(approved);
        stats.setPendingReviewCount(pendingReview);
        stats.setRejectedCount(rejected);
        stats.setTotalRulesEvaluated(total);

        if (total > 0) {
            stats.setApprovedPct(round2((double) approved / total * 100));
            stats.setPendingReviewPct(round2((double) pendingReview / total * 100));
            stats.setRejectedPct(round2((double) rejected / total * 100));
        }

        // Score distribution buckets
        long b0 = repo.countBucket0();
        long b1 = repo.countBucket1();
        long b2 = repo.countBucket2();
        long b3 = repo.countBucket3();
        stats.setBucket0Count(b0);
        stats.setBucket0Pct(total > 0 ? round2((double) b0 / total * 100) : 0);
        stats.setBucket1Count(b1);
        stats.setBucket1Pct(total > 0 ? round2((double) b1 / total * 100) : 0);
        stats.setBucket2Count(b2);
        stats.setBucket2Pct(total > 0 ? round2((double) b2 / total * 100) : 0);
        stats.setBucket3Count(b3);
        stats.setBucket3Pct(total > 0 ? round2((double) b3 / total * 100) : 0);

        // Policy enforcement counts
        stats.setAutoApproveCount(repo.countByDecisionReason("POLICY_APPROVE"));
        stats.setManualReviewCount(
                repo.countByDecisionReason("UNCERTAINTY") + repo.countByDecisionReason("PENDING_REVIEW"));
        stats.setHardBlockCount(
                repo.countByDecisionReason("POLICY_REJECT") + repo.countByDecisionReason("HARD_LIMIT"));
        stats.setVelocityCount(repo.countByDecisionReason("VELOCITY_HARD_LIMIT"));
        stats.setGeoAnomalyCount(repo.countByDecisionReason("GEO_ANOMALY"));

        // Model info
        stats.setAvgInferenceMs(repo.avgInferenceMs());
        var versions = repo.findLatestModelVersions(PageRequest.of(0, 1));
        stats.setModelVersion(versions.isEmpty() ? "v2.4" : versions.get(0));

        return ResponseEntity.ok(ApiResponse.of(stats));
    }

    private double round2(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
