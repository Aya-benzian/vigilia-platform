package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service orchestrating financial transaction ingestion, business validation,
 * idempotency, funds reservation/settlement, and risk decision application.
 */
public interface TransactionService {

    /**
     * Ingests, reserves funds, evaluates risk, and finalizes a transaction.
     *
     * @param request        Transaction creation parameters
     * @param idempotencyKey Optional client Idempotency-Key header
     * @param clientIp       Client IP address for audit and ML features
     * @return Authoritative transaction response
     */
    TransactionResponse createTransaction(TransactionCreateRequest request, String idempotencyKey, String clientIp);

    /**
     * Retrieves a transaction by its UUID.
     */
    TransactionResponse getTransactionById(UUID id);

    /**
     * Lists transactions with optional filters and pagination.
     */
    TransactionPageResponse listTransactions(UUID customerId, RiskStatus status, TransactionType type, Pageable pageable);

    /**
     * Resolves a transaction suspended in PENDING_REVIEW by applying a human analyst decision.
     */
    TransactionResponse resolveAnalystDecision(UUID transactionId, RiskStatus decision, String reason, String analystId);
}
