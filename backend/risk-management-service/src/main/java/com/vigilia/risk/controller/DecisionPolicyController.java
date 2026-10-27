package com.vigilia.risk.controller;

import com.vigilia.risk.dto.ApiResponse;
import com.vigilia.risk.dto.DecisionPolicyResponse;
import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.exception.ResourceNotFoundException;
import com.vigilia.risk.repository.DecisionPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for consulting active risk decision policies.
 * Base path: /api/v1/risk/policies
 *
 * GET /api/v1/risk/policies/active
 *   - without query param: returns all active policies
 *   - with ?type=PURCHASE|TRANSFER: returns the single active policy for that type
 */
@RestController
@RequestMapping("/api/v1/risk/policies")
public class DecisionPolicyController {

    private static final Logger log = LoggerFactory.getLogger(DecisionPolicyController.class);

    private final DecisionPolicyRepository policyRepository;

    public DecisionPolicyController(DecisionPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /**
     * Retrieves active decision policies.
     *
     * @param type optional filter for transaction type (PURCHASE or TRANSFER)
     * @return 200 OK with the active policy (if type specified) or list of active policies
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<?>> getActivePolicies(
            @RequestParam(value = "type", required = false) String type) {

        if (type != null && !type.isBlank()) {
            String normalizedType = type.trim().toUpperCase();
            if (!"PURCHASE".equals(normalizedType) && !"TRANSFER".equals(normalizedType)) {
                throw new IllegalArgumentException("Type de transaction invalide : '" + type + "'. Valeurs autorisées : PURCHASE, TRANSFER");
            }

            log.debug("[RISK] Fetching active policy for transactionType={}", normalizedType);
            DecisionPolicyEntity policy = policyRepository.findByTransactionTypeAndIsActiveTrue(normalizedType)
                    .orElseThrow(() -> new ResourceNotFoundException("Aucune politique active trouvée pour le type : " + normalizedType));

            return ResponseEntity.ok(ApiResponse.of(DecisionPolicyResponse.fromEntity(policy)));
        }

        log.debug("[RISK] Fetching all active decision policies");
        List<DecisionPolicyResponse> policies = policyRepository.findByIsActiveTrue()
                .stream()
                .map(DecisionPolicyResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(policies));
    }
}
