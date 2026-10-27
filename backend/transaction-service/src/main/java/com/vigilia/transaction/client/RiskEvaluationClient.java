package com.vigilia.transaction.client;

import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;

/**
 * Internal client interface for requesting synchronous transaction fraud evaluation
 * from the Risk Management Service.
 */
public interface RiskEvaluationClient {

    /**
     * Synchronously evaluates a transaction against the Risk Management Service rules and ML model.
     *
     * @param request Transaction features and identification
     * @return Authoritative risk decision (APPROVED, REJECTED, PENDING_REVIEW)
     */
    RiskEvaluationResponse evaluate(RiskEvaluationRequest request);
}
