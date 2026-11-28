package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.LinkFundingCardRequest;
import com.vigilia.transaction.dto.LinkedFundingCardResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TopUpRequest;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.exception.ForbiddenException;
import com.vigilia.transaction.exception.UnauthorizedException;
import com.vigilia.transaction.security.SecurityContextHelper;
import com.vigilia.transaction.service.TransactionService;
import com.vigilia.transaction.service.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
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
    private final WalletService walletService;
    private final SecurityContextHelper securityContextHelper;

    public TransactionController(TransactionService transactionService, WalletService walletService, SecurityContextHelper securityContextHelper) {
        this.transactionService = transactionService;
        this.walletService = walletService;
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

        HttpStatus status = (response.getStatus() == RiskStatus.PENDING)
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id:[0-9a-fA-F\\-]{36}}")
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

    @GetMapping("/wallet")
    public ResponseEntity<WalletResponse> getWallet(
            @RequestParam(value = "customerId", required = false) UUID customerId,
            HttpServletRequest request) {

        UUID effectiveCustomerId = customerId;
        if (!securityContextHelper.hasPrivilegedRole(request)) {
            UUID authenticatedUserId = securityContextHelper.requireCurrentUserId(request);
            if (customerId != null && !authenticatedUserId.equals(customerId)) {
                throw new ForbiddenException("Accès refusé : vous ne pouvez pas consulter le portefeuille d'un tiers");
            }
            effectiveCustomerId = authenticatedUserId;
        } else if (effectiveCustomerId == null) {
            effectiveCustomerId = securityContextHelper.resolveCurrentUserId(request);
        }

        if (effectiveCustomerId == null) {
            throw new UnauthorizedException("Identité utilisateur non authentifiée");
        }

        WalletResponse wallet = walletService.getWalletByCustomerId(effectiveCustomerId);
        return ResponseEntity.ok(wallet);
    }

    @PatchMapping("/wallet/currency")
    public ResponseEntity<WalletResponse> changeWalletCurrency(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        String targetCurrency = body != null ? body.get("currency") : null;
        return ResponseEntity.ok(walletService.changeCurrency(customerId, targetCurrency));
    }

    @PatchMapping("/wallet/freeze")
    public ResponseEntity<WalletResponse> setWalletCardFrozen(
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        boolean frozen = body != null && Boolean.TRUE.equals(body.get("frozen"));
        return ResponseEntity.ok(walletService.setCardFrozen(customerId, frozen));
    }

    @PostMapping("/topup")
    public ResponseEntity<WalletResponse> topUp(
            @Valid @RequestBody TopUpRequest request,
            HttpServletRequest httpRequest) {

        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        String clientIp = resolveClientIp(httpRequest);

        WalletResponse wallet = transactionService.topUpWallet(
                customerId,
                request.getAmount(),
                request.getCardBrand(),
                request.getCardLast4(),
                clientIp
        );
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/funding-cards")
    public ResponseEntity<List<LinkedFundingCardResponse>> listFundingCards(HttpServletRequest httpRequest) {
        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        return ResponseEntity.ok(transactionService.listFundingCards(customerId));
    }

    @PostMapping("/funding-cards")
    public ResponseEntity<LinkedFundingCardResponse> linkFundingCard(
            @Valid @RequestBody LinkFundingCardRequest request,
            HttpServletRequest httpRequest) {
        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        LinkedFundingCardResponse created = transactionService.linkFundingCard(customerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/funding-cards/{id}")
    public ResponseEntity<Void> unlinkFundingCard(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        UUID customerId = securityContextHelper.requireCurrentUserId(httpRequest);
        transactionService.unlinkFundingCard(customerId, id);
        return ResponseEntity.noContent().build();
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
