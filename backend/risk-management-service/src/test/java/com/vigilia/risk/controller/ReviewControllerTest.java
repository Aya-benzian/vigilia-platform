package com.vigilia.risk.controller;

import com.vigilia.risk.entity.ReviewCaseEntity;
import com.vigilia.risk.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewController unit tests")
class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReviewController controller = new ReviewController(reviewService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    private ReviewCaseEntity createOpenCase(UUID caseId) {
        ReviewCaseEntity rc = new ReviewCaseEntity();
        rc.setId(caseId);
        rc.setTransactionId(UUID.randomUUID());
        rc.setStatus("OPEN");
        rc.setPriority("MEDIUM");
        rc.setSlaDueAt(Instant.now().plusSeconds(14400));
        rc.setOpenedAt(Instant.now());
        rc.setVersion(0L);
        return rc;
    }

    @Test
    @DisplayName("E3: GET /api/v1/risk/reviews/pending returns 200 and only OPEN status reviews")
    void listPendingReviews_alignedRoute_returns200AndOpenReviews() throws Exception {
        UUID caseId = UUID.randomUUID();
        ReviewCaseEntity openCase = createOpenCase(caseId);
        Page<ReviewCaseEntity> page = new PageImpl<>(List.of(openCase), PageRequest.of(0, 20), 1);

        when(reviewService.listOpenCases(any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/risk/reviews/pending")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(caseId.toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        verify(reviewService).listOpenCases(any());
    }

    @Test
    @DisplayName("E3: GET /api/v1/risk/reviews (without /pending) returns 404 Not Found")
    void getReviews_withoutPending_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/risk/reviews")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
