package com.vigilia.transaction.controller;

import com.vigilia.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransactionStatsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TransactionRepository repo;

    @InjectMocks
    private TransactionStatsController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("getStats returns aggregated stats successfully")
    void getStats_success() throws Exception {
        when(repo.count()).thenReturn(100L);
        when(repo.countByTransactionType("PURCHASE")).thenReturn(80L);
        when(repo.countByTransactionType("TRANSFER")).thenReturn(20L);
        when(repo.sumAmountByType("PURCHASE")).thenReturn(BigDecimal.valueOf(4000.00));
        when(repo.sumAmountByType("TRANSFER")).thenReturn(BigDecimal.valueOf(1000.00));
        when(repo.avgAmountByType("PURCHASE")).thenReturn(BigDecimal.valueOf(50.00));
        when(repo.avgAmountByType("TRANSFER")).thenReturn(BigDecimal.valueOf(50.00));
        when(repo.countApprovedByType("PURCHASE")).thenReturn(76L);
        when(repo.countApprovedByType("TRANSFER")).thenReturn(19L);

        mockMvc.perform(get("/api/v1/transactions/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransactions").value(100))
                .andExpect(jsonPath("$.data.purchaseCount").value(80))
                .andExpect(jsonPath("$.data.transferCount").value(20))
                .andExpect(jsonPath("$.data.purchasePct").value(80.0))
                .andExpect(jsonPath("$.data.transferPct").value(20.0))
                .andExpect(jsonPath("$.data.purchaseVolume").value(4000.00))
                .andExpect(jsonPath("$.data.transferVolume").value(1000.00))
                .andExpect(jsonPath("$.data.purchasePassRate").value(95.0))
                .andExpect(jsonPath("$.data.transferPassRate").value(95.0));
    }

    @Test
    @DisplayName("getStats handles zero total transactions without division by zero")
    void getStats_empty() throws Exception {
        when(repo.count()).thenReturn(0L);
        when(repo.countByTransactionType("PURCHASE")).thenReturn(0L);
        when(repo.countByTransactionType("TRANSFER")).thenReturn(0L);
        when(repo.sumAmountByType("PURCHASE")).thenReturn(BigDecimal.ZERO);
        when(repo.sumAmountByType("TRANSFER")).thenReturn(BigDecimal.ZERO);
        when(repo.avgAmountByType("PURCHASE")).thenReturn(BigDecimal.ZERO);
        when(repo.avgAmountByType("TRANSFER")).thenReturn(BigDecimal.ZERO);
        when(repo.countApprovedByType("PURCHASE")).thenReturn(0L);
        when(repo.countApprovedByType("TRANSFER")).thenReturn(0L);

        mockMvc.perform(get("/api/v1/transactions/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransactions").value(0))
                .andExpect(jsonPath("$.data.purchaseCount").value(0))
                .andExpect(jsonPath("$.data.transferCount").value(0));
    }
}

