package com.vigilia.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullNotificationLifecycleWorks() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        String createPayload = """
                {
                  "eventId": "%s",
                  "eventType": "TRANSACTION_APPROVED",
                  "customerId": "%s",
                  "channel": "IN_APP",
                  "title": "Transaction Approved",
                  "message": "Payment to Netflix · $12.99 was approved successfully.",
                  "metadata": { "amount": 12.99, "currency": "USD" }
                }
                """.formatted(eventId, userId);

        String createRes = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.title").value("Transaction Approved"))
                .andExpect(jsonPath("$.data.category").value("TRANSACTION"))
                .andExpect(jsonPath("$.data.read").value(false))
                .andReturn().getResponse().getContentAsString();

        String notifId = objectMapper.readTree(createRes).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1));

        mockMvc.perform(patch("/api/v1/notifications/" + notifId + "/read")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));

        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promotionalUpdates\": true, \"emailNotifications\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionalUpdates").value(true))
                .andExpect(jsonPath("$.data.emailNotifications").value(false));

        mockMvc.perform(delete("/api/v1/notifications/" + notifId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void requiresAuthenticatedUserHeader() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }
}

