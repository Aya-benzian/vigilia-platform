package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.exception.ForbiddenException;
import com.vigilia.transaction.security.SecurityContextHelper;
import com.vigilia.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for transaction ingestion, evaluation, and history queries.
 * Conforms to contracts/api/transaction-openapi.yaml.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final SecurityContextHelper securityContextHelper;

    public TransactionController(TransactionService transactionService, SecurityContextHelper securityContextHelper) {
        this.transactionService = transactionService;
        this.securityContextHelper = securityContextHelper;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody TransactionCreateRequest requestDto,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        // Anti-spoofing security check: verify that non-privileged callers only create transactions for themselves
        UUID authenticatedUserId = securityContextHelper.resolveCurrentUserId(request);
        if (authenticatedUserId != null && !securityContextHelper.hasPrivilegedRole(request)) {
            if (requestDto.getCustomerId() == null) {
                requestDto.setCustomerId(authenticatedUserId);
            } else if (!authenticatedUserId.equals(requestDto.getCustomerId())) {
                log.warn("Tentative d'usurpation d'identité : utilisateur {} a tenté d'émettre pour client {}",
                        authenticatedUserId, requestDto.getCustomerId());
                throw new ForbiddenException("Vous ne pouvez pas initier une transaction au nom d'un tiers");
            }
        }

        String clientIp = resolveClientIp(request);
        TransactionResponse response = transactionService.createTransaction(requestDto, idempotencyKey, clientIp);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransactionById(
            @PathVariable("id") UUID id,
            HttpServletRequest request) {

        TransactionResponse response = transactionService.getTransactionById(id);

        // Security check: Customer can only view their own transaction; Analyst/Admin can view any
        securityContextHelper.assertOwnershipOrPrivileged(request, response.getCustomerId());

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<TransactionPageResponse> listTransactions(
            @RequestParam(value = "customerId", required = false) UUID customerId,
            @RequestParam(value = "status", required = false) RiskStatus status,
            @RequestParam(value = "type", required = false) TransactionType type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {

        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);

        UUID effectiveCustomerId = customerId;

        // Security check: regular customers are strictly restricted to their own transactions
        if (!securityContextHelper.hasPrivilegedRole(request)) {
            UUID authenticatedUserId = securityContextHelper.requireCurrentUserId(request);
            if (customerId != null && !authenticatedUserId.equals(customerId)) {
                throw new ForbiddenException("Accès refusé : vous ne pouvez pas consulter l'historique d'un tiers");
            }
            effectiveCustomerId = authenticatedUserId;
        }

        TransactionPageResponse response = transactionService.listTransactions(
                effectiveCustomerId, status, type, PageRequest.of(safePage, safeSize)
        );

        return ResponseEntity.ok(response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }
}
