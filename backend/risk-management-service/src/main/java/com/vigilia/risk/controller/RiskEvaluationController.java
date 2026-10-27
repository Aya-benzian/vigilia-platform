package com.vigilia.risk.controller;

import com.vigilia.risk.dto.ApiResponse;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.RiskEvaluationResponse;
import com.vigilia.risk.service.RiskEvaluationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for risk evaluation endpoints.
 * Base path: /api/v1/risk
 *
 * POST  /api/v1/risk/evaluate        → evaluate a transaction
 * GET   /api/v1/risk/{transactionId} → get evaluation result by transaction ID
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(RiskEvaluationController.class);

    private final RiskEvaluationService evaluationService;

    public RiskEvaluationController(RiskEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * Evaluates a transaction for fraud risk.
     * Idempotent: repeated calls with the same transactionId return the cached result.
     *
     * @param request validated risk evaluation request from the Transaction Service
     * @return 200 OK with the evaluation result
     */
    @PostMapping("/evaluate")
    public ResponseEntity<ApiResponse<RiskEvaluationResponse>> evaluate(
            @Valid @RequestBody RiskEvaluationRequest request) {

        log.info("[RISK] Evaluation request received for transactionId={}", request.getTransactionId());
        RiskEvaluationResponse response = evaluationService.evaluate(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Retrieves the risk evaluation for a given transaction ID.
     *
     * @param transactionId the UUID of the transaction
     * @return 200 OK with the evaluation, or 404 if not found
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<ApiResponse<RiskEvaluationResponse>> getByTransactionId(
            @PathVariable UUID transactionId) {

        log.debug("[RISK] Fetch evaluation for transactionId={}", transactionId);
        RiskEvaluationResponse response = evaluationService.findByTransactionId(transactionId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
