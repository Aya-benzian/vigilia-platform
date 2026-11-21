package com.vigilia.risk.controller;

import com.vigilia.risk.repository.RiskEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RiskStatsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RiskEvaluationRepository repo;

    @InjectMocks
    private RiskStatsController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("getLiveStats returns aggregated risk telemetry correctly")
    void getLiveStats_success() throws Exception {
        when(repo.count()).thenReturn(100L);
        when(repo.countByDecision("APPROVED")).thenReturn(85L);
        when(repo.countByDecision("PENDING_REVIEW")).thenReturn(10L);
        when(repo.countByDecision("REJECTED")).thenReturn(5L);

        when(repo.countBucket0()).thenReturn(70L);
        when(repo.countBucket1()).thenReturn(15L);
        when(repo.countBucket2()).thenReturn(10L);
        when(repo.countBucket3()).thenReturn(5L);

        when(repo.countByDecisionReason("POLICY_APPROVE")).thenReturn(80L);
        when(repo.countByDecisionReason("UNCERTAINTY")).thenReturn(6L);
        when(repo.countByDecisionReason("PENDING_REVIEW")).thenReturn(4L);
        when(repo.countByDecisionReason("POLICY_REJECT")).thenReturn(3L);
        when(repo.countByDecisionReason("HARD_LIMIT")).thenReturn(2L);
        when(repo.countByDecisionReason("VELOCITY_HARD_LIMIT")).thenReturn(1L);
        when(repo.countByDecisionReason("GEO_ANOMALY")).thenReturn(2L);

        when(repo.avgInferenceMs()).thenReturn(42.5);
        when(repo.findLatestModelVersions(any(Pageable.class))).thenReturn(List.of("2.1.0"));

        mockMvc.perform(get("/api/v1/risk/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalEvaluated").value(100))
                .andExpect(jsonPath("$.data.approvedCount").value(85))
                .andExpect(jsonPath("$.data.pendingReviewCount").value(10))
                .andExpect(jsonPath("$.data.rejectedCount").value(5))
                .andExpect(jsonPath("$.data.approvedPct").value(85.0))
                .andExpect(jsonPath("$.data.pendingReviewPct").value(10.0))
                .andExpect(jsonPath("$.data.rejectedPct").value(5.0))
                .andExpect(jsonPath("$.data.modelVersion").value("2.1.0"));
    }

    @Test
    @DisplayName("getLiveStats handles empty evaluations correctly")
    void getLiveStats_empty() throws Exception {
        when(repo.count()).thenReturn(0L);
        when(repo.countByDecision("APPROVED")).thenReturn(0L);
        when(repo.countByDecision("PENDING_REVIEW")).thenReturn(0L);
        when(repo.countByDecision("REJECTED")).thenReturn(0L);

        when(repo.avgInferenceMs()).thenReturn(0.0);
        when(repo.findLatestModelVersions(any(Pageable.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/risk/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalEvaluated").value(0))
                .andExpect(jsonPath("$.data.modelVersion").value("v2.4"));
    }
}

