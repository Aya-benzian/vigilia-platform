package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.LinkFundingCardRequest;
import com.vigilia.transaction.dto.LinkedFundingCardResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.dto.WalletResponse;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
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

    /**
     * Top-up / deposit funds into customer wallet using a linked external funding card.
     */
    WalletResponse topUpWallet(UUID customerId, BigDecimal amount, String cardBrand, String cardLast4, String clientIp);

    /**
     * Lists all linked external funding cards for the customer.
     */
    List<LinkedFundingCardResponse> listFundingCards(UUID customerId);

    /**
     * Links a new external bank debit/credit card as a funding source.
     */
    LinkedFundingCardResponse linkFundingCard(UUID customerId, LinkFundingCardRequest request);

    /**
     * Unlinks an external funding card belonging to the customer.
     */
    void unlinkFundingCard(UUID customerId, UUID cardId);
}
