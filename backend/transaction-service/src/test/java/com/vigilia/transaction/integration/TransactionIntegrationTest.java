package com.vigilia.transaction.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.transaction.client.RiskEvaluationClient;
import com.vigilia.transaction.dto.RiskEvaluationResponse;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.repository.IdempotencyKeyRepository;
import com.vigilia.transaction.repository.MerchantRepository;
import com.vigilia.transaction.repository.OutboxEventRepository;
import com.vigilia.transaction.repository.PaymentRepository;
import com.vigilia.transaction.repository.TransactionRepository;
import com.vigilia.transaction.repository.TransactionStatusHistoryRepository;
import com.vigilia.transaction.repository.TransferRepository;
import com.vigilia.transaction.repository.WalletLedgerRepository;
import com.vigilia.transaction.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 10 — Integration tests for transaction-service.
 *
 * Covers all Step 10 objectives:
 *  - PURCHASE flow: wallet hold, risk call, settlement (APPROVED)
 *  - PURCHASE flow: wallet hold release on risk REJECTED
 *  - PURCHASE flow: wallet hold maintained on PENDING_REVIEW
 *  - Insufficient funds check
 *  - TRANSFER flow: double-wallet lock ordering, debit sender, credit recipient (APPROVED)
 *  - TRANSFER to own account forbidden
 *  - Idempotency replay: cached response returned, wallet not double-debited
 *  - Anti-spoofing security: non-privileged caller cannot create for another customer
 *  - GET transaction by ID: ownership verification
 *  - Outbox event published atomically with transaction completion
 *
 * Uses H2 in-memory DB (MODE=PostgreSQL) with mock RiskEvaluationClient.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Tag("integration")
class TransactionIntegrationTest extends PostgresIntegrationBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionStatusHistoryRepository statusHistoryRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletLedgerRepository ledgerRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private RiskEvaluationClient riskEvaluationClient;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("SET session_replication_role = replica");
        try {
            jdbcTemplate.execute("DELETE FROM transaction.wallet_ledger");
            transferRepository.deleteAll();
            paymentRepository.deleteAll();
            statusHistoryRepository.deleteAll();
            idempotencyRepository.deleteAll();
            outboxEventRepository.deleteAll();
            transactionRepository.deleteAll();
            merchantRepository.deleteAll();
            walletRepository.deleteAll();
        } finally {
            jdbcTemplate.execute("SET session_replication_role = DEFAULT");
        }
    }

    private WalletEntity createWallet(UUID customerId, String iban, BigDecimal balance) {
        WalletEntity wallet = new WalletEntity(customerId, iban, "EUR", balance);
        return walletRepository.save(wallet);
    }

    private RiskEvaluationResponse mockRiskResponse(RiskStatus status, String reason) {
        RiskEvaluationResponse resp = new RiskEvaluationResponse();
        resp.setEvaluationId(UUID.randomUUID());
        resp.setDecision(status);
        resp.setDecisionReason(reason);
        resp.setFraudProbability(status == RiskStatus.APPROVED ? 0.05 : 0.85);
        resp.setEvaluatedAt(Instant.now());
        return resp;
    }

    // =========================================================================
    // PURCHASE Tests
    // =========================================================================

    @Test
    @DisplayName("1. PURCHASE - APPROVED: funds settled, wallet balance decreased, outbox written")
    void purchase_Approved_SettlesFundsAndEmitsOutbox() throws Exception {
        UUID customerId = UUID.randomUUID();
        createWallet(customerId, "FR7630006000011234567890189", new BigDecimal("500.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.APPROVED, "POLICY_APPROVE"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.PURCHASE, new BigDecimal("100.00"), "EUR");
        req.setCategory("SUPERMARKET");
        req.setMerchantName("Carrefour");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.amount", is(100.00)))
                .andExpect(jsonPath("$.currency", is("EUR")))
                .andExpect(jsonPath("$.id", notNullValue()));

        // Verify wallet balance: 500 - 100 = 400 available, 0 held
        WalletEntity updatedWallet = walletRepository.findByCustomerId(customerId).orElseThrow();
        assertThat(updatedWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(updatedWallet.getHeldBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Verify outbox event written
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        var outbox = outboxEventRepository.findAll().get(0);
        assertThat(outbox.getEventType()).isEqualTo("TRANSACTION_COMPLETED");
        assertThat(outbox.getPayload()).contains("APPROVED");

        // Verify status history
        var history = statusHistoryRepository.findAll();
        assertThat(history).hasSize(2); // PENDING, then APPROVED
    }

    @Test
    @DisplayName("2. PURCHASE - REJECTED: funds released, wallet balance fully restored")
    void purchase_Rejected_ReleasesHeldFunds() throws Exception {
        UUID customerId = UUID.randomUUID();
        createWallet(customerId, "FR7630006000011234567890188", new BigDecimal("300.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.REJECTED, "HARD_LIMIT"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.PURCHASE, new BigDecimal("150.00"), "EUR");
        req.setCategory("ELECTRONICS");
        req.setMerchantName("Fnac");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        // Verify wallet balance: restored to 300, held = 0
        WalletEntity updatedWallet = walletRepository.findByCustomerId(customerId).orElseThrow();
        assertThat(updatedWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(updatedWallet.getHeldBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // Verify outbox emitted with REJECTED
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("3. PURCHASE - PENDING_REVIEW: funds remain held pending analyst decision")
    void purchase_PendingReview_KeepsFundsHeld() throws Exception {
        UUID customerId = UUID.randomUUID();
        createWallet(customerId, "FR7630006000011234567890187", new BigDecimal("500.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.PENDING_REVIEW, "UNCERTAINTY"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.PURCHASE, new BigDecimal("200.00"), "EUR");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING_REVIEW")));

        // Available balance: 500 - 200 = 300; held balance: 200
        WalletEntity updatedWallet = walletRepository.findByCustomerId(customerId).orElseThrow();
        assertThat(updatedWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(updatedWallet.getHeldBalance()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("4. Insufficient funds returns 422 Unprocessable Entity")
    void purchase_InsufficientFunds_Returns422() throws Exception {
        UUID customerId = UUID.randomUUID();
        createWallet(customerId, "FR7630006000011234567890186", new BigDecimal("50.00"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.PURCHASE, new BigDecimal("500.00"), "EUR");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_FUNDS")));

        // Balance unchanged
        WalletEntity wallet = walletRepository.findByCustomerId(customerId).orElseThrow();
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(wallet.getHeldBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // TRANSFER Tests
    // =========================================================================

    @Test
    @DisplayName("5. TRANSFER - APPROVED: debits sender, credits recipient")
    void transfer_Approved_TransfersFundsBetweenWallets() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        String recipientIban = "FR7630006000011234567890185";

        createWallet(senderId, "FR7630006000011234567890184", new BigDecimal("1000.00"));
        createWallet(recipientId, recipientIban, new BigDecimal("200.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.APPROVED, "POLICY_APPROVE"));

        TransactionCreateRequest req = new TransactionCreateRequest(senderId, TransactionType.TRANSFER, new BigDecimal("300.00"), "EUR");
        req.setRecipientIban(recipientIban);
        req.setTransferReference("Loyer Novembre");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", senderId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.type", is("TRANSFER")));

        // Sender: 1000 - 300 = 700
        WalletEntity senderWallet = walletRepository.findByCustomerId(senderId).orElseThrow();
        assertThat(senderWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("700.00"));

        // Recipient: 200 + 300 = 500
        WalletEntity recipientWallet = walletRepository.findByCustomerId(recipientId).orElseThrow();
        assertThat(recipientWallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("500.00"));

        // Transfer entity created
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("6. TRANSFER to self returns 400 Bad Request")
    void transfer_ToSelf_Returns400() throws Exception {
        UUID customerId = UUID.randomUUID();
        String iban = "FR7630006000011234567890183";
        createWallet(customerId, iban, new BigDecimal("500.00"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.TRANSFER, new BigDecimal("50.00"), "EUR");
        req.setRecipientIban(iban);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Idempotency Tests
    // =========================================================================

    @Test
    @DisplayName("7. Idempotency replay returns cached response without double-debit")
    void idempotency_DuplicateRequest_ReturnsCachedResponse() throws Exception {
        UUID customerId = UUID.randomUUID();
        createWallet(customerId, "FR7630006000011234567890182", new BigDecimal("1000.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.APPROVED, "POLICY_APPROVE"));

        TransactionCreateRequest req = new TransactionCreateRequest(customerId, TransactionType.PURCHASE, new BigDecimal("100.00"), "EUR");
        String idempotencyKey = UUID.randomUUID().toString();
        String json = objectMapper.writeValueAsString(req);

        // First call
        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Replay with identical key
        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")));

        // Wallet should only be debited once (1000 - 100 = 900)
        WalletEntity wallet = walletRepository.findByCustomerId(customerId).orElseThrow();
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("900.00"));

        // Only 1 transaction in DB
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // =========================================================================
    // Security & Anti-Spoofing Tests
    // =========================================================================

    @Test
    @DisplayName("8. Anti-spoofing: customer cannot initiate transaction for another customer (403)")
    void security_CustomerSpoofing_Returns403() throws Exception {
        UUID attackerId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        createWallet(victimId, "FR7630006000011234567890181", new BigDecimal("500.00"));

        TransactionCreateRequest req = new TransactionCreateRequest(victimId, TransactionType.PURCHASE, new BigDecimal("50.00"), "EUR");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", attackerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9. GET /api/v1/transactions/{id} - owner access 200, stranger access 403")
    void security_GetTransaction_OwnershipCheck() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        createWallet(ownerId, "FR7630006000011234567890180", new BigDecimal("500.00"));

        when(riskEvaluationClient.evaluate(any())).thenReturn(mockRiskResponse(RiskStatus.APPROVED, "POLICY_APPROVE"));

        TransactionCreateRequest req = new TransactionCreateRequest(ownerId, TransactionType.PURCHASE, new BigDecimal("40.00"), "EUR");
        String responseContent = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-User-Id", ownerId.toString())
                        .header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var txnJson = objectMapper.readTree(responseContent);
        String txnId = txnJson.get("id").asText();

        // Stranger access -> 403
        mockMvc.perform(get("/api/v1/transactions/{id}", txnId)
                        .header("X-User-Id", strangerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden());

        // Owner access -> 200
        mockMvc.perform(get("/api/v1/transactions/{id}", txnId)
                        .header("X-User-Id", ownerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(txnId)))
                .andExpect(jsonPath("$.customerId", is(ownerId.toString())));
    }
}
