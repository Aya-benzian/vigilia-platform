package com.vigilia.customer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.customer.dto.*;
import com.vigilia.customer.entity.OutboxEventEntity;
import com.vigilia.customer.entity.ProcessedEventEntity;
import com.vigilia.customer.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerPreferenceRepository preferenceRepository;

    @Autowired
    private BeneficiaryRepository beneficiaryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void cleanUp() {
        beneficiaryRepository.deleteAll();
        preferenceRepository.deleteAll();
        customerRepository.deleteAll();
        outboxEventRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Flux complet d'intégration : Ingestion d'inscription, profil, préférences, bénéficiaire et outbox")
    void shouldExecuteCompleteCustomerFlow() throws Exception {
        UUID eventId1 = UUID.randomUUID();
        UUID userId1 = UUID.randomUUID();

        // 1. Ingestion de l'événement USER_REGISTERED pour le premier utilisateur
        UserRegisteredEventPayload event1 = new UserRegisteredEventPayload(
                eventId1, userId1, "alice.smith@example.com", "Alice Smith"
        );
        event1.setDateOfBirth(LocalDate.of(1991, 7, 23));
        event1.setHomeCity("Paris");
        event1.setHomeCountry("FR");
        event1.setHomeLat(new BigDecimal("48.856614"));
        event1.setHomeLon(new BigDecimal("2.352222"));
        event1.setCityPopulation(2161000);

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId", is(userId1.toString())))
                .andExpect(jsonPath("$.data.fullName", is("Alice Smith")));

        // Vérification de l'idempotence : second envoi du même événement
        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId", is(userId1.toString())));

        // Il ne doit y avoir qu'un seul profil client pour userId1
        assertThat(customerRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.existsById(eventId1)).isTrue();

        // Vérification de l'Outbox event CUSTOMER_CREATED
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).anyMatch(e -> "CUSTOMER_CREATED".equals(e.getEventType()));

        // 2. Consultation du profil par le client authentifié
        String aliceAccountNumber = customerRepository.findByUserId(userId1).orElseThrow().getAccountNumber();

        mockMvc.perform(get("/api/v1/customers/me")
                        .header("X-User-Id", userId1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName", is("Alice Smith")))
                .andExpect(jsonPath("$.data.accountNumber", is(aliceAccountNumber)));

        // 3. Mise à jour du profil client
        CustomerUpdateRequest updateRequest = new CustomerUpdateRequest();
        updateRequest.setFullName("Alice S. Dupont");
        updateRequest.setHomeCity("Versailles");

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("X-User-Id", userId1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName", is("Alice S. Dupont")))
                .andExpect(jsonPath("$.data.homeCity", is("Versailles")));

        // 4. Consultation et mise à jour des préférences
        mockMvc.perform(get("/api/v1/customers/me/preferences")
                        .header("X-User-Id", userId1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifications", is(true)))
                .andExpect(jsonPath("$.data.language", is("en")));

        CustomerPreferenceUpdateRequest prefUpdate = new CustomerPreferenceUpdateRequest(false, true, "fr");
        mockMvc.perform(put("/api/v1/customers/me/preferences")
                        .header("X-User-Id", userId1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prefUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifications", is(false)))
                .andExpect(jsonPath("$.data.language", is("fr")));

        // 5. Inscription d'un second utilisateur (Bob)
        UUID eventId2 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        UserRegisteredEventPayload event2 = new UserRegisteredEventPayload(
                eventId2, userId2, "bob.jones@example.com", "Bob Jones"
        );
        event2.setDateOfBirth(LocalDate.of(1989, 11, 5));
        event2.setHomeCity("Marseille");
        event2.setHomeCountry("FR");

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event2)))
                .andExpect(status().isCreated());

        String bobAccountNumber = customerRepository.findByUserId(userId2).orElseThrow().getAccountNumber();

        // 6. Recherche anonymisée (lookup) du compte de Bob par son numéro
        mockMvc.perform(get("/api/v1/customers/lookup")
                        .param("account_number", bobAccountNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists", is(true)))
                .andExpect(jsonPath("$.data.display_name", is("Bob*** J.")));

        // 7. Alice ajoute Bob comme bénéficiaire
        BeneficiaryCreateRequest addBeneficiaryReq = new BeneficiaryCreateRequest(bobAccountNumber, "Bob Colleague");
        mockMvc.perform(post("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addBeneficiaryReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recipientAccountNumber", is(bobAccountNumber)))
                .andExpect(jsonPath("$.data.nickname", is("Bob Colleague")));

        // Alice tente d'ajouter Bob une seconde fois -> 409 Conflit
        mockMvc.perform(post("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addBeneficiaryReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_RESOURCE")));

        // Alice tente de s'ajouter elle-même -> 400 Bad Request
        BeneficiaryCreateRequest addSelfReq = new BeneficiaryCreateRequest(aliceAccountNumber, "Myself");
        mockMvc.perform(post("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId1.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addSelfReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        // 8. Consultation des bénéficiaires par Alice
        mockMvc.perform(get("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].recipientAccountNumber", is(bobAccountNumber)));

        // 9. API interne de profil de risque pour Risk Management
        mockMvc.perform(get("/internal/customers/" + userId1 + "/risk-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId", is(userId1.toString())))
                .andExpect(jsonPath("$.data.homeCity", is("Versailles")))
                .andExpect(jsonPath("$.data.accountNumber", is(aliceAccountNumber)));
    }
}
