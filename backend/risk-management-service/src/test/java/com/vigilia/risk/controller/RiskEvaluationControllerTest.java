package com.vigilia.risk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vigilia.risk.dto.RiskEvaluationRequest;
import com.vigilia.risk.dto.RiskEvaluationResponse;
import com.vigilia.risk.dto.RiskStatus;
import com.vigilia.risk.dto.TransactionType;
import com.vigilia.risk.exception.GlobalExceptionHandler;
import com.vigilia.risk.service.RiskEvaluationService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskEvaluationController unit tests")
class RiskEvaluationControllerTest {

    @Mock
    private RiskEvaluationService evaluationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RiskEvaluationController controller = new RiskEvaluationController(evaluationService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("POST /api/v1/risk/evaluate returns 200 with flat RiskEvaluationResponse without data or meta envelope")
    void evaluate_returns200WithFlatResponse_contractCompliant() throws Exception {
        UUID evalId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        UUID txnId = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        Instant evalTime = Instant.parse("2026-10-25T22:30:01Z");

        RiskEvaluationResponse mockResponse = new RiskEvaluationResponse(
                evalId,
                txnId,
                RiskStatus.APPROVED,
                0.04,
                0.01,
                "purchase-fraud-model:1.0.0",
                "POLICY_APPROVE",
                evalTime
        );

        when(evaluationService.evaluate(any(RiskEvaluationRequest.class))).thenReturn(mockResponse);

        RiskEvaluationRequest request = new RiskEvaluationRequest();
        request.setTransactionId(txnId);
        request.setCustomerId(UUID.randomUUID());
        request.setTransactionType(TransactionType.PURCHASE);
        request.setAmount(new BigDecimal("99.99"));
        request.setCurrency("EUR");
        request.setTimestamp(Instant.now());

        mockMvc.perform(post("/api/v1/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.evaluationId").value(evalId.toString()))
                .andExpect(jsonPath("$.transactionId").value(txnId.toString()))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.fraudProbability").value(0.04))
                .andExpect(jsonPath("$.uncertainty").value(0.01))
                .andExpect(jsonPath("$.decisionReason").value("POLICY_APPROVE"))
                .andExpect(jsonPath("$.evaluatedAt").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta").doesNotExist());

        verify(evaluationService).evaluate(any(RiskEvaluationRequest.class));
    }
}
