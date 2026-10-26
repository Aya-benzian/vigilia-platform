package com.vigilia.risk.controller;

import com.vigilia.risk.entity.DecisionPolicyEntity;
import com.vigilia.risk.exception.GlobalExceptionHandler;
import com.vigilia.risk.repository.DecisionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("DecisionPolicyController unit tests")
class DecisionPolicyControllerTest {

    @Mock
    private DecisionPolicyRepository policyRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DecisionPolicyController controller = new DecisionPolicyController(policyRepository);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private DecisionPolicyEntity buildPolicy(String type) {
        DecisionPolicyEntity p = new DecisionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setTransactionType(type);
        p.setVersion(1);
        p.setRejectThreshold(new BigDecimal("0.70000"));
        p.setApproveThreshold(new BigDecimal("0.30000"));
        p.setUncertaintyThreshold(new BigDecimal("0.50000"));
        p.setMlTimeoutMs(1500);
        p.setMaxAmountHardLimit(new BigDecimal("5000.00"));
        p.setVelocityHardCap1h(15);
        p.setCostAssumptions("{\"internal_secret_cost\": 42}");
        p.setCreatedBy(UUID.randomUUID());
        p.setIsActive(true);
        p.setCreatedAt(Instant.parse("2026-10-25T12:00:00Z"));
        return p;
    }

    @Test
    @DisplayName("1. GET /api/v1/risk/policies/active sans paramètre retourne 200 OK avec toutes les politiques actives")
    void getActivePolicies_noParam_returnsAllActivePolicies() throws Exception {
        DecisionPolicyEntity purchasePolicy = buildPolicy("PURCHASE");
        DecisionPolicyEntity transferPolicy = buildPolicy("TRANSFER");

        when(policyRepository.findByIsActiveTrue()).thenReturn(List.of(purchasePolicy, transferPolicy));

        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].transactionType").value("PURCHASE"))
                .andExpect(jsonPath("$.data[1].transactionType").value("TRANSFER"));

        verify(policyRepository).findByIsActiveTrue();
    }

    @Test
    @DisplayName("2. GET /api/v1/risk/policies/active?type=PURCHASE retourne 200 OK avec la politique PURCHASE")
    void getActivePolicies_purchaseType_returnsPurchasePolicy() throws Exception {
        DecisionPolicyEntity purchasePolicy = buildPolicy("PURCHASE");

        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(purchasePolicy));

        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .param("type", "PURCHASE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(purchasePolicy.getId().toString()))
                .andExpect(jsonPath("$.data.transactionType").value("PURCHASE"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.rejectThreshold").value(0.70000))
                .andExpect(jsonPath("$.data.approveThreshold").value(0.30000))
                .andExpect(jsonPath("$.data.uncertaintyThreshold").value(0.50000))
                .andExpect(jsonPath("$.data.mlTimeoutMs").value(1500))
                .andExpect(jsonPath("$.data.maxAmountHardLimit").value(5000.00))
                .andExpect(jsonPath("$.data.velocityHardCap1h").value(15))
                .andExpect(jsonPath("$.data.isActive").value(true));

        verify(policyRepository).findByTransactionTypeAndIsActiveTrue("PURCHASE");
    }

    @Test
    @DisplayName("3. GET /api/v1/risk/policies/active?type=TRANSFER retourne 200 OK avec la politique TRANSFER")
    void getActivePolicies_transferType_returnsTransferPolicy() throws Exception {
        DecisionPolicyEntity transferPolicy = buildPolicy("TRANSFER");

        when(policyRepository.findByTransactionTypeAndIsActiveTrue("TRANSFER"))
                .thenReturn(Optional.of(transferPolicy));

        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .param("type", "TRANSFER")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("TRANSFER"))
                .andExpect(jsonPath("$.data.isActive").value(true));

        verify(policyRepository).findByTransactionTypeAndIsActiveTrue("TRANSFER");
    }

    @Test
    @DisplayName("4. GET /api/v1/risk/policies/active?type=PURCHASE retourne 404 quand aucune politique n'existe")
    void getActivePolicies_notFound_returns404() throws Exception {
        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .param("type", "PURCHASE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("5. GET /api/v1/risk/policies/active?type=INVALID retourne 400 Bad Request")
    void getActivePolicies_invalidType_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .param("type", "INVALID_TYPE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("6. Absence de champs internes non exposés dans le JSON (costAssumptions, createdBy)")
    void getActivePolicies_doesNotExposeInternalFields() throws Exception {
        DecisionPolicyEntity policy = buildPolicy("PURCHASE");

        when(policyRepository.findByTransactionTypeAndIsActiveTrue("PURCHASE"))
                .thenReturn(Optional.of(policy));

        mockMvc.perform(get("/api/v1/risk/policies/active")
                        .param("type", "PURCHASE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Verify internal fields are strictly omitted from JSON output
                .andExpect(jsonPath("$.data.costAssumptions").doesNotExist())
                .andExpect(jsonPath("$.data.createdBy").doesNotExist());
    }
}
