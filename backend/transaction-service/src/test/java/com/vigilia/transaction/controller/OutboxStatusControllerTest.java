package com.vigilia.transaction.controller;

import com.vigilia.transaction.repository.OutboxEventRepository;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OutboxStatusControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OutboxEventRepository outboxRepo;

    @InjectMocks
    private OutboxStatusController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("getOutboxStatus returns healthy when pending and failed are 0")
    void getOutboxStatus_healthy() throws Exception {
        when(outboxRepo.countByStatus("PENDING")).thenReturn(0L);
        when(outboxRepo.countByStatus("PUBLISHED")).thenReturn(100L);
        when(outboxRepo.countByStatus("FAILED")).thenReturn(0L);

        mockMvc.perform(get("/api/v1/transactions/outbox/status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(0))
                .andExpect(jsonPath("$.data.published").value(100))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.healthy").value(true));
    }

    @Test
    @DisplayName("getOutboxStatus returns unhealthy when pending or failed > 0")
    void getOutboxStatus_unhealthy() throws Exception {
        when(outboxRepo.countByStatus("PENDING")).thenReturn(5L);
        when(outboxRepo.countByStatus("PUBLISHED")).thenReturn(50L);
        when(outboxRepo.countByStatus("FAILED")).thenReturn(2L);

        mockMvc.perform(get("/api/v1/transactions/outbox/status")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(5))
                .andExpect(jsonPath("$.data.published").value(50))
                .andExpect(jsonPath("$.data.failed").value(2))
                .andExpect(jsonPath("$.data.healthy").value(false));
    }
}

