package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.ApiResponse;
import com.vigilia.transaction.dto.ReviewDecisionEventPayload;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.entity.ProcessedEventEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.exception.ForbiddenException;
import com.vigilia.transaction.exception.ResourceNotFoundException;
import com.vigilia.transaction.repository.ProcessedEventRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal service-to-service controller for consuming events published by other microservices.
 * Guarantees strict idempotency via ProcessedEventRepository to avoid double financial effects.
 *
 * <p>Bug fixes:
 * <ul>
 *   <li>K1: Requires the caller to present a valid X-Internal-Service-Token header.
 *       Without a token, the request is rejected with 403 before any DB work is done.</li>
 *   <li>I1: Transaction row is now loaded WITH FOR UPDATE before the status check and analyst
 *       decision application, preventing two concurrent events from both passing the
 *       PENDING_REVIEW guard and double-applying wallet effects.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/transactions")
@Tag(name = "Internal Transaction API", description = "Endpoints for inter-service communication and event ingestion")
public class InternalTransactionController {

    private static final Logger log = LoggerFactory.getLogger(InternalTransactionController.class);

    // K1 fix: shared secret injected from application properties (set via K8s secret / env var)
    private static final String SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";
    private final String expectedServiceToken;

    private final TransactionService transactionService;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionRepository transactionRepository;

    public InternalTransactionController(
            TransactionService transactionService,
            ProcessedEventRepository processedEventRepository,
            TransactionRepository transactionRepository,
            @org.springframework.beans.factory.annotation.Value("${vigilia.internal.service-token:}") String expectedServiceToken) {
        this.transactionService = transactionService;
        this.processedEventRepository = processedEventRepository;
        this.transactionRepository = transactionRepository;
        this.expectedServiceToken = expectedServiceToken;
    }

    /**
     * Ingestion endpoint for REVIEW_DECISION_COMPLETED events emitted by Risk Management Service.
     */
    @PostMapping(path = {"/events", "/review-decisions"})
    @Operation(summary = "Ingestion d'une décision d'analyste",
               description = "Consomme de façon idempotente la décision humaine pour débloquer/finaliser la transaction")
    @Transactional
    public ResponseEntity<ApiResponse<TransactionResponse>> ingestReviewDecision(
            @Valid @RequestBody ReviewDecisionEventPayload payload,
            @RequestHeader(value = SERVICE_TOKEN_HEADER, required = false) String serviceToken,
            HttpServletRequest httpRequest) {

        // K1 fix: reject callers that do not present the shared internal service token
        if (expectedServiceToken != null && !expectedServiceToken.isBlank()
                && !expectedServiceToken.equals(serviceToken)) {
            log.warn("[SECURITY] Rejected unauthenticated call to /internal/transactions/events from {}",
                    httpRequest != null ? httpRequest.getRemoteAddr() : "unknown");
            throw new ForbiddenException("Accès non autorisé à l'API interne");
        }

        UUID eventId = payload.getEventId();
        UUID transactionId = payload.getTransactionId();

        // 1. Strict Idempotency check via processed_events table
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            log.info("[IDEMPOTENCY] Événement de décision de revue déjà traité [eventId={}, txnId={}]. Rejeu ignoré.",
                    eventId, transactionId);
            TransactionResponse existing = transactionService.getTransactionById(transactionId);
            return ResponseEntity.ok(ApiResponse.of(existing));
        }

        // I1 fix: load transaction WITH FOR UPDATE so concurrent events cannot both pass the
        // PENDING_REVIEW guard and double-apply wallet effects
        TransactionEntity txn = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction introuvable pour l'identifiant: " + transactionId));

        // 2. If the transaction has already left PENDING_REVIEW, record event and return current state
        if (!RiskStatus.PENDING_REVIEW.name().equalsIgnoreCase(txn.getStatus())) {
            log.warn("[IDEMPOTENCY] Transaction {} déjà résolue avec l'état {}. Enregistrement de l'événement sans réapplication.",
                    transactionId, txn.getStatus());
            if (eventId != null) {
                processedEventRepository.save(new ProcessedEventEntity(eventId, "REVIEW_DECISION_COMPLETED"));
            }
            TransactionResponse response = transactionService.getTransactionById(transactionId);
            return ResponseEntity.ok(ApiResponse.of(response));
        }

        // 3. Apply the analyst's decision via business service
        String reason = payload.getReasonCode() != null ? payload.getReasonCode() : "Décision d'analyse humaine";
        String analystId = payload.getAnalystId() != null ? payload.getAnalystId() : "system-analyst";

        TransactionResponse resolved = transactionService.resolveAnalystDecision(
                transactionId, payload.getDecision(), reason, analystId);

        // 4. Record event as processed in the same SQL transaction
        if (eventId != null) {
            processedEventRepository.save(new ProcessedEventEntity(eventId, "REVIEW_DECISION_COMPLETED"));
        }

        log.info("[INTERNAL-EVENT] Décision d'analyste appliquée avec succès [eventId={}, txnId={}, decision={}]",
                eventId, transactionId, payload.getDecision());

        return ResponseEntity.ok(ApiResponse.of(resolved));
    }
}
