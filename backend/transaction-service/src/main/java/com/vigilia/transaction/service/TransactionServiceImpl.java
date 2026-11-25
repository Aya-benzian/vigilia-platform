package com.vigilia.transaction.service;

import com.vigilia.transaction.client.RiskEvaluationClient;
import com.vigilia.transaction.dto.IdempotencyCheckResult;
import com.vigilia.transaction.dto.LinkFundingCardRequest;
import com.vigilia.transaction.dto.LinkedFundingCardResponse;
import com.vigilia.transaction.dto.PaginationMeta;
import com.vigilia.transaction.dto.RiskEvaluationRequest;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.entity.LinkedFundingCardEntity;
import com.vigilia.transaction.entity.MerchantEntity;
import com.vigilia.transaction.entity.PaymentEntity;
import com.vigilia.transaction.entity.TransactionEntity;
import com.vigilia.transaction.entity.TransactionStatusHistoryEntity;
import com.vigilia.transaction.entity.TransferEntity;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.exception.ResourceNotFoundException;
import com.vigilia.transaction.repository.LinkedFundingCardRepository;
import com.vigilia.transaction.repository.MerchantRepository;
import com.vigilia.transaction.repository.PaymentRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.repository.TransactionStatusHistoryRepository;
import com.vigilia.transaction.repository.TransferRepository;
import com.vigilia.transaction.repository.WalletRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core implementation of TransactionService orchestrating purchase and transfer flows,
 * deterministic wallet lock ordering, risk scoring integration, and immutable audit logs.
 */
@Service
@Transactional(readOnly = true)
public class TransactionServiceImpl implements TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceImpl.class);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final TransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final TransferRepository transferRepository;
    private final TransactionStatusHistoryRepository statusHistoryRepository;
    private final WalletRepository walletRepository;
    private final MerchantRepository merchantRepository;
    private final LinkedFundingCardRepository linkedFundingCardRepository;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final RiskEvaluationClient riskEvaluationClient;
    private final TransactionOutboxService transactionOutboxService;

    public TransactionServiceImpl(
            TransactionRepository transactionRepository,
            PaymentRepository paymentRepository,
            TransferRepository transferRepository,
            TransactionStatusHistoryRepository statusHistoryRepository,
            WalletRepository walletRepository,
            MerchantRepository merchantRepository,
            LinkedFundingCardRepository linkedFundingCardRepository,
            WalletService walletService,
            IdempotencyService idempotencyService,
            RiskEvaluationClient riskEvaluationClient,
            TransactionOutboxService transactionOutboxService) {
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.transferRepository = transferRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.walletRepository = walletRepository;
        this.merchantRepository = merchantRepository;
        this.linkedFundingCardRepository = linkedFundingCardRepository;
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
        this.riskEvaluationClient = riskEvaluationClient;
        this.transactionOutboxService = transactionOutboxService;
    }

    @Override
    @Transactional
    public TransactionResponse createTransaction(TransactionCreateRequest request, String idempotencyKey, String clientIp) {
        validateRequest(request);

        String scope = null;
        String requestHash = null;

        // 1. Idempotency verification before ANY financial effect
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            scope = idempotencyService.buildScope(request.getCustomerId());
            requestHash = idempotencyService.calculateRequestHash(request);

            IdempotencyCheckResult checkResult = idempotencyService.checkOrThrow(scope, idempotencyKey, requestHash);
            if (checkResult.isCached()) {
                log.info("Rejeu idempotent pour la clé '{}' : restitution de la réponse cachée", idempotencyKey);
                Optional<TransactionResponse> cachedResponse = idempotencyService.parseResponseBody(
                        checkResult.getResponseBody(), TransactionResponse.class);
                if (cachedResponse.isPresent()) {
                    return cachedResponse.get();
                }
                // Fallback if cache body unparseable: reload from DB
                return transactionRepository.findByCustomerIdAndIdempotencyKey(request.getCustomerId(), idempotencyKey)
                        .map(this::toResponse)
                        .orElseThrow(() -> new IllegalStateException("Réponse en cache absente de la base"));
            }
        }

        // 2. Dispatch according to transaction domain type
        TransactionResponse response;
        if (request.getType() == TransactionType.PURCHASE) {
            response = executePurchase(request, idempotencyKey, clientIp);
        } else if (request.getType() == TransactionType.TRANSFER) {
            response = executeTransfer(request, idempotencyKey, clientIp);
        } else {
            throw new InvalidOperationException("Type de transaction non pris en charge: " + request.getType());
        }

        // 3. Cache idempotent response
        if (idempotencyKey != null && !idempotencyKey.isBlank() && scope != null && requestHash != null) {
            idempotencyService.recordResponse(scope, idempotencyKey, requestHash, 201, response, null);
        }

        return response;
    }

    private TransactionResponse executePurchase(TransactionCreateRequest request, String idempotencyKey, String clientIp) {
        UUID customerId = request.getCustomerId();
        BigDecimal amount = request.getAmount();

        WalletEntity customerWallet = walletService.getWalletEntityByCustomerId(customerId);
        if (customerWallet.isCardFrozen()) {
            throw new InvalidOperationException("Your Vigilia virtual card is frozen. Unfreeze it in My Wallet to make payments.");
        }
        validateCurrency(customerWallet.getCurrency(), request.getCurrency());

        // Resolve or create merchant record
        String merchantName = (request.getMerchantName() != null && !request.getMerchantName().isBlank())
                ? request.getMerchantName().trim()
                : "Default Merchant";
        String merchantCategory = (request.getCategory() != null && !request.getCategory().isBlank())
                ? request.getCategory().trim().toUpperCase()
                : "RETAIL";

        MerchantEntity merchant = resolveOrCreateMerchant(merchantName, merchantCategory);

        // A. Reserve funds (HOLD) under pessimistic lock
        walletService.holdFunds(customerWallet.getId(), null, amount);

        // B. Persist transaction in PENDING state
        TransactionEntity txn = new TransactionEntity(
                TransactionType.PURCHASE.name(),
                customerId,
                amount,
                request.getCurrency().trim().toUpperCase(),
                "PENDING"
        );
        txn.setIdempotencyKey(idempotencyKey);
        txn.setIpAddress(clientIp);
        TransactionEntity savedTxn = transactionRepository.save(txn);

        // C. Persist purchase details
        PaymentEntity payment = new PaymentEntity(savedTxn.getId(), merchant.getId());
        payment.setMerchantCategory(merchantCategory);
        payment.setProductName(merchantName);
        if (request.getMerchantLatitude() != null) {
            payment.setCustomerLat(BigDecimal.valueOf(request.getMerchantLatitude()));
        }
        if (request.getMerchantLongitude() != null) {
            payment.setCustomerLon(BigDecimal.valueOf(request.getMerchantLongitude()));
        }
        paymentRepository.save(payment);

        // D. Record initial state in status history
        recordStatusHistory(savedTxn.getId(), null, "PENDING", "Initialisation et réservation des fonds", "SYSTEM", "system");

        // E. Call Risk Evaluation Service
        RiskEvaluationRequest riskReq = new RiskEvaluationRequest(
                savedTxn.getId(),
                customerId,
                TransactionType.PURCHASE,
                amount,
                request.getCurrency(),
                Instant.now()
        );
        riskReq.setMerchantId(merchant != null ? merchant.getId().toString() : null);
        riskReq.setMerchantCategory(merchantCategory);
        riskReq.setMerchantLatitude(request.getMerchantLatitude());
        riskReq.setMerchantLongitude(request.getMerchantLongitude());

        RiskEvaluationResponse riskDecision = riskEvaluationClient.evaluate(riskReq);

        // F. Apply Authoritative Risk Decision
        applyDecision(savedTxn, customerWallet.getId(), null, amount, riskDecision);

        TransactionEntity finalized = transactionRepository.save(savedTxn);
        return toResponse(finalized, riskDecision);
    }

    private TransactionResponse executeTransfer(TransactionCreateRequest request, String idempotencyKey, String clientIp) {
        UUID senderCustomerId = request.getCustomerId();
        BigDecimal amount = request.getAmount();

        if (request.getRecipientIban() == null || request.getRecipientIban().isBlank()) {
            throw new InvalidOperationException("recipientIban est obligatoire pour un virement (TRANSFER)");
        }

        String recipientIban = request.getRecipientIban().replaceAll("\\s+", "").toUpperCase();

        WalletEntity senderWallet = walletService.getWalletEntityByCustomerId(senderCustomerId);
        if (senderWallet.isCardFrozen()) {
            throw new InvalidOperationException("Your Vigilia virtual card is frozen. Unfreeze it in My Wallet to send transfers.");
        }
        WalletEntity recipientWallet = walletRepository.findByAccountNumber(recipientIban)
                .orElseThrow(() -> new ResourceNotFoundException("Portefeuille destinataire introuvable pour l'IBAN: " + recipientIban));

        if (senderWallet.getId().equals(recipientWallet.getId())) {
            throw new InvalidOperationException("Impossible d'effectuer un virement vers son propre compte");
        }

        validateCurrency(senderWallet.getCurrency(), request.getCurrency());
        validateCurrency(senderWallet.getCurrency(), recipientWallet.getCurrency());

        // A. Deterministic Lock Ordering to prevent deadlocks:
        // Always acquire pessimistic lock on the smaller UUID first, then the larger UUID.
        UUID firstLockId = senderWallet.getId().compareTo(recipientWallet.getId()) < 0 ? senderWallet.getId() : recipientWallet.getId();
        UUID secondLockId = senderWallet.getId().compareTo(recipientWallet.getId()) < 0 ? recipientWallet.getId() : senderWallet.getId();

        walletRepository.findByIdForUpdate(firstLockId);
        walletRepository.findByIdForUpdate(secondLockId);

        // B. Reserve funds (HOLD) from sender wallet
        walletService.holdFunds(senderWallet.getId(), null, amount);

        // C. Persist transaction in PENDING state
        TransactionEntity txn = new TransactionEntity(
                TransactionType.TRANSFER.name(),
                senderCustomerId,
                amount,
                request.getCurrency().trim().toUpperCase(),
                "PENDING"
        );
        txn.setIdempotencyKey(idempotencyKey);
        txn.setIpAddress(clientIp);
        TransactionEntity savedTxn = transactionRepository.save(txn);

        // D. Persist transfer details
        TransferEntity transfer = new TransferEntity(
                savedTxn.getId(),
                senderWallet.getId(),
                recipientWallet.getId(),
                recipientWallet.getCustomerId(),
                request.getTransferReference()
        );
        transferRepository.save(transfer);

        // E. Record initial state in status history
        recordStatusHistory(savedTxn.getId(), null, "PENDING", "Initialisation du transfert et réservation des fonds expéditeur", "SYSTEM", "system");

        // F. Call Risk Evaluation Service
        RiskEvaluationRequest riskReq = new RiskEvaluationRequest(
                savedTxn.getId(),
                senderCustomerId,
                TransactionType.TRANSFER,
                amount,
                request.getCurrency(),
                Instant.now()
        );
        riskReq.setRecipientId(recipientWallet.getCustomerId() != null ? recipientWallet.getCustomerId().toString() : null);
        riskReq.setSenderBalance(senderWallet.getAvailableBalance() != null ? senderWallet.getAvailableBalance().doubleValue() : null);

        RiskEvaluationResponse riskDecision = riskEvaluationClient.evaluate(riskReq);

        // G. Apply Authoritative Risk Decision
        applyDecision(savedTxn, senderWallet.getId(), recipientWallet.getId(), amount, riskDecision);

        TransactionEntity finalized = transactionRepository.save(savedTxn);
        return toResponse(finalized, riskDecision);
    }

    private void applyDecision(TransactionEntity txn, UUID senderWalletId, UUID recipientWalletId, BigDecimal amount, RiskEvaluationResponse riskDecision) {
        RiskStatus decision = riskDecision.getDecision();
        String reason = riskDecision.getDecisionReason() != null ? riskDecision.getDecisionReason() : "Decision: " + decision;

        switch (decision) {
            case APPROVED -> {
                // Settle sender held funds
                walletService.settleHold(senderWalletId, txn.getId(), amount);

                // If transfer, credit recipient wallet
                if (recipientWalletId != null) {
                    walletService.creditFunds(recipientWalletId, txn.getId(), amount);
                }

                txn.setStatus(RiskStatus.APPROVED.name());
                txn.setFinalizedAt(Instant.now());
                recordStatusHistory(txn.getId(), "PENDING", "APPROVED", reason, "SYSTEM", "system");
                transactionOutboxService.publishTransactionCompleted(txn, RiskStatus.APPROVED, null);
                log.info("Transaction validée et réglée [id={}, type={}, amount={}]", txn.getId(), txn.getTransactionType(), amount);
            }
            case REJECTED -> {
                // Release sender held funds back to available balance
                walletService.releaseFunds(senderWalletId, txn.getId(), amount);
                // Recipient wallet is never credited

                txn.setStatus(RiskStatus.REJECTED.name());
                txn.setFinalizedAt(Instant.now());
                recordStatusHistory(txn.getId(), "PENDING", "REJECTED", reason, "SYSTEM", "system");
                transactionOutboxService.publishTransactionCompleted(txn, RiskStatus.REJECTED, null);
                log.warn("Transaction rejetée et fonds restitués [id={}, type={}, reason={}]", txn.getId(), txn.getTransactionType(), reason);
            }
            case PENDING_REVIEW -> {
                // Funds remain reserved in held balance pending manual analyst decision
                txn.setStatus(RiskStatus.PENDING_REVIEW.name());
                recordStatusHistory(txn.getId(), "PENDING", "PENDING_REVIEW", reason, "SYSTEM", "system");
                log.warn("Transaction suspendue en attente d'analyse humaine [id={}, type={}]", txn.getId(), txn.getTransactionType());
            }
        }
    }

    @Override
    @Transactional
    public TransactionResponse resolveAnalystDecision(UUID transactionId, RiskStatus decision, String reason, String analystId) {
        if (transactionId == null) {
            throw new InvalidOperationException("transactionId ne doit pas être null");
        }
        if (decision == null || decision == RiskStatus.PENDING_REVIEW) {
            throw new InvalidOperationException("La décision de l'analyste doit être APPROVED ou REJECTED");
        }

        TransactionEntity txn = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction introuvable: " + transactionId));

        if (!RiskStatus.PENDING_REVIEW.name().equalsIgnoreCase(txn.getStatus())) {
            throw new InvalidOperationException(
                    "Seules les transactions en état PENDING_REVIEW peuvent être résolues (état actuel: " + txn.getStatus() + ")");
        }

        String fromStatus = txn.getStatus();
        BigDecimal amount = txn.getAmount();

        if (TransactionType.PURCHASE.name().equalsIgnoreCase(txn.getTransactionType())) {
            WalletEntity wallet = walletService.getWalletEntityByCustomerId(txn.getCustomerId());
            if (decision == RiskStatus.APPROVED) {
                walletService.settleHold(wallet.getId(), txn.getId(), amount);
            } else {
                walletService.releaseFunds(wallet.getId(), txn.getId(), amount);
            }
        } else if (TransactionType.TRANSFER.name().equalsIgnoreCase(txn.getTransactionType())) {
            TransferEntity transfer = transferRepository.findByTransactionId(txn.getId())
                    .orElseThrow(() -> new IllegalStateException("Détail de transfert introuvable pour la transaction: " + txn.getId()));

            if (decision == RiskStatus.APPROVED) {
                walletService.settleHold(transfer.getSenderWalletId(), txn.getId(), amount);
                walletService.creditFunds(transfer.getRecipientWalletId(), txn.getId(), amount);
            } else {
                walletService.releaseFunds(transfer.getSenderWalletId(), txn.getId(), amount);
            }
        }

        txn.setStatus(decision.name());
        txn.setFinalizedAt(Instant.now());
        TransactionEntity saved = transactionRepository.save(txn);

        recordStatusHistory(txn.getId(), fromStatus, decision.name(), reason, "ANALYST", analystId);
        transactionOutboxService.publishTransactionResolved(saved, decision, reason, analystId, null);
        log.info("Décision analyste appliquée [txnId={}, fromStatus={}, toStatus={}, analyst={}]",
                txn.getId(), fromStatus, decision.name(), analystId);

        return toResponse(saved);
    }

    @Override
    @Transactional
    public WalletResponse topUpWallet(UUID customerId, BigDecimal amount, String cardBrand, String cardLast4, String clientIp) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Le montant du rechargement doit être supérieur à zéro");
        }

        WalletEntity wallet = walletService.getWalletEntityByCustomerId(customerId);

        // Enforce that the customer has at least one linked external funding card
        List<LinkedFundingCardEntity> linkedCards = linkedFundingCardRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        if (linkedCards.isEmpty()) {
            throw new InvalidOperationException("No linked bank card found. Please link an external funding card before topping up your wallet.");
        }

        LinkedFundingCardEntity selectedCard;
        if (cardLast4 != null && !cardLast4.isBlank()) {
            selectedCard = linkedCards.stream()
                    .filter(c -> c.getCardLast4().equals(cardLast4.trim()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidOperationException("Selected funding card (•••• " + cardLast4.trim() + ") is not linked to your account."));
        } else {
            selectedCard = linkedCards.stream()
                    .filter(LinkedFundingCardEntity::isDefault)
                    .findFirst()
                    .orElse(linkedCards.get(0));
        }

        String brand = selectedCard.getCardBrand();
        String last4 = selectedCard.getCardLast4();
        String merchantName = "Wallet Deposit (" + brand + " •••• " + last4 + ")";
        MerchantEntity merchant = resolveOrCreateMerchant(merchantName, "DEPOSIT");

        TransactionEntity txn = new TransactionEntity(
                TransactionType.DEPOSIT.name(),
                customerId,
                amount,
                wallet.getCurrency(),
                RiskStatus.APPROVED.name()
        );
        txn.setFinalizedAt(Instant.now());
        txn.setIpAddress(clientIp);
        TransactionEntity savedTxn = transactionRepository.saveAndFlush(txn);
        UUID txnId = savedTxn.getId();

        // 2. Credit the wallet ledger
        walletService.creditFunds(wallet.getId(), txnId, amount);

        // 3. Record payment details
        PaymentEntity payment = new PaymentEntity(txnId, merchant.getId());
        payment.setProductId("DEPOSIT_FUNDS");
        payment.setProductName(merchantName);
        payment.setMerchantCategory("DEPOSIT");
        payment.setCardBrand(brand);
        payment.setCardLast4(last4);
        paymentRepository.save(payment);

        recordStatusHistory(txnId, "PENDING", "APPROVED", "Sandbox direct card deposit", "SYSTEM", "gateway-simulator");
        log.info("Top-up effectué avec succès pour le client [customerId={}, amount={}, card={}****{}]", customerId, amount, brand, last4);

        return walletService.getWalletByCustomerId(customerId);
    }

    @Override
    public TransactionResponse getTransactionById(UUID id) {
        if (id == null) {
            throw new InvalidOperationException("id ne doit pas être null");
        }
        return transactionRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction introuvable pour l'identifiant: " + id));
    }

    @Override
    public TransactionPageResponse listTransactions(UUID customerId, RiskStatus status, TransactionType type, Pageable pageable) {
        Specification<TransactionEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status.name()));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("transactionType"), type.name()));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<TransactionEntity> page = transactionRepository.findAll(spec, pageable);
        List<TransactionResponse> content = page.getContent().stream().map(this::toResponse).toList();
        PaginationMeta meta = new PaginationMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());

        return new TransactionPageResponse(content, meta);
    }

    private MerchantEntity resolveOrCreateMerchant(String name, String category) {
        return merchantRepository.findByNameIgnoreCase(name)
                .orElseGet(() -> {
                    String cleanCode = "MCH_" + name.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
                    if (cleanCode.length() > 50) {
                        cleanCode = cleanCode.substring(0, 50);
                    }
                    if (merchantRepository.findByMerchantCode(cleanCode).isPresent()) {
                        cleanCode = cleanCode.substring(0, Math.min(cleanCode.length(), 40)) + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                    }
                    MerchantEntity newMerchant = new MerchantEntity(cleanCode, name, category);
                    return merchantRepository.save(newMerchant);
                });
    }

    private void recordStatusHistory(UUID txnId, String fromStatus, String toStatus, String reason, String actorType, String actorId) {
        TransactionStatusHistoryEntity history = new TransactionStatusHistoryEntity(
                txnId, fromStatus, toStatus, reason, actorType, actorId
        );
        statusHistoryRepository.save(history);
    }

    private void validateRequest(TransactionCreateRequest request) {
        if (request == null) {
            throw new InvalidOperationException("La requête de transaction ne peut pas être null");
        }
        if (request.getCustomerId() == null) {
            throw new InvalidOperationException("customerId est obligatoire");
        }
        if (request.getType() == null) {
            throw new InvalidOperationException("type de transaction est obligatoire (PURCHASE ou TRANSFER)");
        }
        if (request.getAmount() == null) {
            throw new InvalidAmountException("amount ne peut pas être null");
        }
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("amount doit être strictement positif");
        }
        if (request.getAmount().scale() > 2) {
            throw new InvalidAmountException("amount ne peut pas comporter plus de 2 décimales");
        }
        if (request.getCurrency() == null || !CURRENCY_PATTERN.matcher(request.getCurrency().trim()).matches()) {
            throw new InvalidOperationException("currency doit être un code ISO 4217 de 3 lettres majuscules");
        }
    }

    private void validateCurrency(String expected, String given) {
        if (!expected.equalsIgnoreCase(given.trim())) {
            throw new InvalidOperationException(String.format(
                    "Incompatibilité de devise : le portefeuille est en %s mais l'opération demande %s",
                    expected, given));
        }
    }

    private TransactionResponse toResponse(TransactionEntity entity) {
        return toResponse(entity, null);
    }

    private TransactionResponse toResponse(TransactionEntity entity, RiskEvaluationResponse risk) {
        RiskStatus statusEnum;
        try {
            statusEnum = RiskStatus.valueOf(entity.getStatus());
        } catch (Exception e) {
            statusEnum = RiskStatus.PENDING_REVIEW;
        }

        Double riskScore = risk != null ? risk.getFraudProbability() : null;
        String reason = risk != null ? risk.getDecisionReason() : "Decision: " + entity.getStatus();

        TransactionType typeEnum;
        try {
            typeEnum = TransactionType.valueOf(entity.getTransactionType());
        } catch (Exception e) {
            typeEnum = TransactionType.PURCHASE;
        }

        TransactionResponse response = new TransactionResponse(
                entity.getId(),
                entity.getCustomerId(),
                typeEnum,
                entity.getAmount(),
                entity.getCurrency(),
                statusEnum,
                riskScore,
                reason,
                entity.getCreatedAt()
        );

        // Enrich with real Payment/Merchant or Transfer details from ledger database
        if (typeEnum == TransactionType.PURCHASE) {
            paymentRepository.findById(entity.getId()).ifPresent(payment -> {
                response.setCategory(payment.getMerchantCategory());
                response.setProductName(payment.getProductName());
                if (payment.getMerchantId() != null) {
                    merchantRepository.findById(payment.getMerchantId()).ifPresent(merchant -> {
                        response.setMerchantName(merchant.getName());
                        if (response.getCategory() == null || response.getCategory().isBlank()) {
                            response.setCategory(merchant.getCategory());
                        }
                    });
                }
            });
        } else if (typeEnum == TransactionType.TRANSFER) {
            transferRepository.findById(entity.getId()).ifPresent(transfer -> {
                response.setCategory("TRANSFER");
                response.setMemo(transfer.getMemo());
                response.setProductName(transfer.getMemo() != null && !transfer.getMemo().isBlank() ? transfer.getMemo() : "Direct Transfer");
                response.setRecipientCustomerId(transfer.getRecipientCustomerId());
                if (transfer.getMemo() != null && !transfer.getMemo().isBlank()) {
                    response.setMerchantName(transfer.getMemo());
                } else {
                    response.setMerchantName("Direct Transfer");
                }
            });
        } else if (typeEnum == TransactionType.DEPOSIT) {
            response.setCategory("DEPOSIT");
            response.setMerchantName("Card Top-Up");
            paymentRepository.findById(entity.getId()).ifPresentOrElse(
                    p -> response.setProductName(p.getProductName() != null ? p.getProductName() : "Wallet Deposit"),
                    () -> response.setProductName("Wallet Deposit")
            );
        }

        return response;
    }

    @Override
    public List<LinkedFundingCardResponse> listFundingCards(UUID customerId) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        return linkedFundingCardRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toFundingCardResponse)
                .toList();
    }

    @Override
    @Transactional
    public LinkedFundingCardResponse linkFundingCard(UUID customerId, LinkFundingCardRequest request) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        String digits = request.getCardNumber() != null ? request.getCardNumber().replaceAll("\\D", "") : "";
        if (digits.length() < 13 || digits.length() > 19 || !isValidLuhn(digits)) {
            throw new InvalidOperationException("Invalid card number (Luhn check failed).");
        }

        String brand = detectCardBrand(digits);
        String last4 = digits.substring(digits.length() - 4);

        Optional<LinkedFundingCardEntity> existing = linkedFundingCardRepository
                .findFirstByCustomerIdAndCardLast4OrderByCreatedAtDesc(customerId, last4);
        if (existing.isPresent() && existing.get().getCardBrand().equalsIgnoreCase(brand)) {
            return toFundingCardResponse(existing.get());
        }

        String[] expParts = request.getExpiry().split("/");
        String expMonth = expParts[0];
        String expYear = expParts[1].length() == 2 ? "20" + expParts[1] : expParts[1];

        LinkedFundingCardEntity card = new LinkedFundingCardEntity();
        card.setCustomerId(customerId);
        card.setCardBrand(brand);
        card.setCardLast4(last4);
        card.setCardholderName(request.getCardholderName() != null ? request.getCardholderName().trim() : null);
        card.setExpiryMonth(expMonth);
        card.setExpiryYear(expYear);
        card.setDefault(!linkedFundingCardRepository.existsByCustomerId(customerId));

        LinkedFundingCardEntity saved = linkedFundingCardRepository.save(card);
        return toFundingCardResponse(saved);
    }

    @Override
    @Transactional
    public void unlinkFundingCard(UUID customerId, UUID cardId) {
        LinkedFundingCardEntity card = linkedFundingCardRepository.findByIdAndCustomerId(cardId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Linked funding card not found"));
        linkedFundingCardRepository.delete(card);
    }

    private LinkedFundingCardResponse toFundingCardResponse(LinkedFundingCardEntity c) {
        String shortYear = c.getExpiryYear().length() == 4 ? c.getExpiryYear().substring(2) : c.getExpiryYear();
        return new LinkedFundingCardResponse(
                c.getId(),
                c.getCardBrand(),
                c.getCardLast4(),
                c.getCardholderName(),
                c.getExpiryMonth() + "/" + shortYear,
                c.isDefault(),
                c.getCreatedAt()
        );
    }

    private static String detectCardBrand(String digits) {
        if (digits.startsWith("4")) return "VISA";
        if (digits.matches("^5[1-5].*")) return "MASTERCARD";
        if (digits.matches("^3[47].*")) return "AMEX";
        if (digits.matches("^6(?:011|5).*")) return "DISCOVER";
        return "CARD";
    }

    private static boolean isValidLuhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}
