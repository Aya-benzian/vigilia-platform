package com.vigilia.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vigilia.transaction.dto.PaginationMeta;
import com.vigilia.transaction.dto.RiskStatus;
import com.vigilia.transaction.dto.TransactionCreateRequest;
import com.vigilia.transaction.dto.TransactionPageResponse;
import com.vigilia.transaction.dto.TransactionResponse;
import com.vigilia.transaction.dto.TransactionType;
import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.exception.GlobalExceptionHandler;
import com.vigilia.transaction.security.SecurityContextHelper;
import com.vigilia.transaction.service.TransactionService;
import com.vigilia.transaction.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private TransactionService transactionService;

    @Mock
    private WalletService walletService;

    @Spy
    private SecurityContextHelper securityContextHelper = new SecurityContextHelper();

    @InjectMocks
    private TransactionController transactionController;

    private UUID customerId;
    private UUID otherCustomerId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(transactionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        customerId = UUID.randomUUID();
        otherCustomerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
    }

    // =========================================================================
    // 1. POST /api/v1/transactions
    // =========================================================================

    @Test
    @DisplayName("POST /api/v1/transactions - Succès 201 avec Idempotency-Key et X-Correlation-Id")
    void createTransaction_Success_201() throws Exception {
        TransactionCreateRequest req = new TransactionCreateRequest(
                customerId,
                TransactionType.PURCHASE,
                new BigDecimal("49.99"),
                "EUR"
        );
        req.setMerchantName("Fnac");

        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(customerId);
        resp.setStatus(RiskStatus.APPROVED);
        resp.setAmount(new BigDecimal("49.99"));
        resp.setCurrency("EUR");
        resp.setTimestamp(Instant.now());

        when(transactionService.createTransaction(any(), eq("key-123"), any()))
                .thenReturn(resp);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", customerId.toString())
                        .header("Idempotency-Key", "key-123")
                        .header("X-Correlation-Id", "corr-abc")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(transactionId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.amount").value(49.99))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions - Anti-spoofing : un client ne peut pas émettre pour un tiers (403 Forbidden)")
    void createTransaction_AntiSpoofing_Forbidden() throws Exception {
        TransactionCreateRequest req = new TransactionCreateRequest(
                otherCustomerId, // Attempting to post on behalf of another user
                TransactionType.PURCHASE,
                new BigDecimal("20.00"),
                "EUR"
        );

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", customerId.toString()) // Authenticated as customerId
                        .header("X-User-Role", "CUSTOMER")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Vous ne pouvez pas initier une transaction au nom d'un tiers"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions - Analyste autorisé à émettre au nom d'un tiers")
    void createTransaction_AnalystAllowedForOther() throws Exception {
        TransactionCreateRequest req = new TransactionCreateRequest(
                otherCustomerId,
                TransactionType.PURCHASE,
                new BigDecimal("20.00"),
                "EUR"
        );

        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(otherCustomerId);
        resp.setStatus(RiskStatus.APPROVED);

        when(transactionService.createTransaction(any(), any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "ANALYST")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(transactionId.toString()));
    }

    // =========================================================================
    // 2. GET /api/v1/transactions/{id}
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/transactions/{id} - Propriétaire autorisé (200 OK)")
    void getTransactionById_Owner_Success_200() throws Exception {
        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(customerId);
        resp.setStatus(RiskStatus.APPROVED);

        when(transactionService.getTransactionById(transactionId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/transactions/" + transactionId)
                        .header("X-User-Id", customerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/transactions/{id} - Client tiers non autorisé (403 Forbidden)")
    void getTransactionById_ThirdPartyCustomer_Forbidden() throws Exception {
        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(otherCustomerId); // Belongs to otherCustomerId
        resp.setStatus(RiskStatus.APPROVED);

        when(transactionService.getTransactionById(transactionId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/transactions/" + transactionId)
                        .header("X-User-Id", customerId.toString())) // Authenticated as customerId
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /api/v1/transactions/{id} - Analyste autorisé à consulter une transaction tierce")
    void getTransactionById_Analyst_Success_200() throws Exception {
        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(otherCustomerId);
        resp.setStatus(RiskStatus.APPROVED);

        when(transactionService.getTransactionById(transactionId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/transactions/" + transactionId)
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/transactions/{id} - Non authentifié : 401 Unauthorized")
    void getTransactionById_Unauthenticated_401() throws Exception {
        TransactionResponse resp = new TransactionResponse();
        resp.setId(transactionId);
        resp.setCustomerId(customerId);

        when(transactionService.getTransactionById(transactionId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/transactions/" + transactionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // =========================================================================
    // 3. GET /api/v1/transactions
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/transactions - Client automatiquement restreint à ses propres transactions")
    void listTransactions_CustomerRestrictedToOwn() throws Exception {
        TransactionResponse item = new TransactionResponse();
        item.setId(transactionId);
        item.setCustomerId(customerId);
        TransactionPageResponse pageResp = new TransactionPageResponse(List.of(item), new PaginationMeta(0, 20, 1, 1));

        when(transactionService.listTransactions(eq(customerId), any(), any(), any(Pageable.class)))
                .thenReturn(pageResp);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(transactionId.toString()))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/transactions - Client tentant de requêter l'historique d'un tiers (403 Forbidden)")
    void listTransactions_CustomerQueryingOtherCustomer_Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", otherCustomerId.toString())
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GET /api/v1/transactions - Analyste autorisé à requêter l'historique d'un tiers")
    void listTransactions_AnalystCanQueryAnyCustomer() throws Exception {
        TransactionPageResponse pageResp = new TransactionPageResponse(List.of(), new PaginationMeta(0, 20, 0, 0));

        when(transactionService.listTransactions(eq(otherCustomerId), any(), any(), any(Pageable.class)))
                .thenReturn(pageResp);

        mockMvc.perform(get("/api/v1/transactions")
                        .param("customerId", otherCustomerId.toString())
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(0));
    }

    // =========================================================================
    // 4. GET /api/v1/transactions/wallet
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/transactions/wallet - Client consulte son propre portefeuille (200 OK)")
    void getWallet_CustomerOwnWallet_Success() throws Exception {
        WalletResponse wallet = new WalletResponse(
                UUID.randomUUID(),
                customerId,
                "VIG-7823-9012-0412",
                "EUR",
                new BigDecimal("2250.00"),
                new BigDecimal("200.00"),
                Instant.now()
        );

        when(walletService.getWalletByCustomerId(customerId)).thenReturn(wallet);

        mockMvc.perform(get("/api/v1/transactions/wallet")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("VIG-7823-9012-0412"))
                .andExpect(jsonPath("$.availableBalance").value(2250.00))
                .andExpect(jsonPath("$.heldBalance").value(200.00))
                .andExpect(jsonPath("$.totalBalance").value(2450.00));
    }

    @Test
    @DisplayName("GET /api/v1/transactions/wallet - Client requêtant le portefeuille d'un tiers (403 Forbidden)")
    void getWallet_CustomerQueryingOtherCustomer_Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/wallet")
                        .param("customerId", otherCustomerId.toString())
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions/topup - Dépôt de fonds réussi (200 OK)")
    void topUp_Success() throws Exception {
        WalletResponse wallet = new WalletResponse(
                UUID.randomUUID(),
                customerId,
                "VIG-7823-9012-0412",
                "USD",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                Instant.now()
        );

        when(transactionService.topUpWallet(eq(customerId), eq(new BigDecimal("100.00")), eq("VISA"), eq("4242"), anyString()))
                .thenReturn(wallet);

        mockMvc.perform(post("/api/v1/transactions/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100.00, \"cardBrand\": \"VISA\", \"cardLast4\": \"4242\"}")
                        .header("X-User-Id", customerId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(100.00))
                .andExpect(jsonPath("$.totalBalance").value(100.00));
    }
}
