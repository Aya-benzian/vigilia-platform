package com.vigilia.customer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.customer.dto.*;
import com.vigilia.customer.exception.DuplicateResourceException;
import com.vigilia.customer.exception.ResourceNotFoundException;
import com.vigilia.customer.security.SecurityContextHelper;
import com.vigilia.customer.service.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CustomerController.class, InternalCustomerController.class})
@Import(SecurityContextHelper.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerService customerService;

    @Test
    @DisplayName("GET /api/v1/customers/me - Succès avec identité X-User-Id injectée")
    void shouldReturnProfileWhenAuthenticated() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerResponse response = new CustomerResponse(
                customerId,
                userId,
                "Jean Dupont",
                LocalDate.of(1985, 4, 12),
                "Paris",
                "FR",
                new BigDecimal("48.856614"),
                new BigDecimal("2.352222"),
                2161000,
                "VGL0000000001",
                Instant.now(),
                Instant.now()
        );

        when(customerService.getProfileByUserId(userId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/me")
                        .header("X-User-Id", userId.toString())
                        .header("X-Correlation-Id", "corr-test-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName", is("Jean Dupont")))
                .andExpect(jsonPath("$.data.accountNumber", is("VGL0000000001")))
                .andExpect(header().string("X-Correlation-Id", "corr-test-1"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me - Échec 401 Unauthorized sans identité X-User-Id")
    void shouldReturn401WhenMissingUserIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")))
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("PATCH /api/v1/customers/me - Succès mise à jour du profil")
    void shouldUpdateProfileSuccessfully() throws Exception {
        UUID userId = UUID.randomUUID();

        CustomerUpdateRequest request = new CustomerUpdateRequest();
        request.setFullName("Jean-Paul Dupont");
        request.setHomeCity("Lyon");
        request.setHomeCountry("FR");

        CustomerResponse response = new CustomerResponse();
        response.setFullName("Jean-Paul Dupont");
        response.setHomeCity("Lyon");

        when(customerService.updateProfile(eq(userId), any(CustomerUpdateRequest.class), any())).thenReturn(response);

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName", is("Jean-Paul Dupont")))
                .andExpect(jsonPath("$.data.homeCity", is("Lyon")));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/preferences - Succès consultation des préférences")
    void shouldReturnPreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerPreferenceResponse response = new CustomerPreferenceResponse(
                customerId, true, true, "fr", Instant.now()
        );

        when(customerService.getPreferences(userId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/me/preferences")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifications", is(true)))
                .andExpect(jsonPath("$.data.language", is("fr")));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/me/preferences - Succès modification des préférences")
    void shouldUpdatePreferences() throws Exception {
        UUID userId = UUID.randomUUID();
        CustomerPreferenceUpdateRequest request = new CustomerPreferenceUpdateRequest(false, true, "en");

        CustomerPreferenceResponse response = new CustomerPreferenceResponse(
                UUID.randomUUID(), false, true, "en", Instant.now()
        );

        when(customerService.updatePreferences(eq(userId), any(CustomerPreferenceUpdateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/customers/me/preferences")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailNotifications", is(false)))
                .andExpect(jsonPath("$.data.language", is("en")));
    }

    @Test
    @DisplayName("GET /api/v1/customers/me/beneficiaries - Consultation des bénéficiaires")
    void shouldReturnBeneficiaries() throws Exception {
        UUID userId = UUID.randomUUID();
        BeneficiaryResponse b1 = new BeneficiaryResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VGL0000000002", "Alice", Instant.now()
        );

        when(customerService.getBeneficiaries(userId)).thenReturn(List.of(b1));

        mockMvc.perform(get("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].recipientAccountNumber", is("VGL0000000002")))
                .andExpect(jsonPath("$.data[0].nickname", is("Alice")));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/beneficiaries - Succès création de bénéficiaire (201 Created)")
    void shouldCreateBeneficiarySuccessfully() throws Exception {
        UUID userId = UUID.randomUUID();
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest("VGL0000000002", "Alice");

        BeneficiaryResponse response = new BeneficiaryResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VGL0000000002", "Alice", Instant.now()
        );

        when(customerService.addBeneficiary(eq(userId), any(BeneficiaryCreateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recipientAccountNumber", is("VGL0000000002")));
    }

    @Test
    @DisplayName("POST /api/v1/customers/me/beneficiaries - Échec 409 Conflit si bénéficiaire déjà existant")
    void shouldReturn409WhenBeneficiaryDuplicate() throws Exception {
        UUID userId = UUID.randomUUID();
        BeneficiaryCreateRequest request = new BeneficiaryCreateRequest("VGL0000000002", "Alice");

        when(customerService.addBeneficiary(eq(userId), any(BeneficiaryCreateRequest.class)))
                .thenThrow(new DuplicateResourceException("Ce bénéficiaire est déjà enregistré pour votre compte"));

        mockMvc.perform(post("/api/v1/customers/me/beneficiaries")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_RESOURCE")))
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/me/beneficiaries/{id} - Succès suppression")
    void shouldDeleteBeneficiary() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID beneficiaryId = UUID.randomUUID();

        doNothing().when(customerService).deleteBeneficiary(userId, beneficiaryId);

        mockMvc.perform(delete("/api/v1/customers/me/beneficiaries/" + beneficiaryId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.message", is("Bénéficiaire supprimé avec succès")));
    }

    @Test
    @DisplayName("GET /api/v1/customers/lookup - Succès recherche avec nom masqué")
    void shouldLookupAccount() throws Exception {
        CustomerLookupResponse response = CustomerLookupResponse.found("VGL0000123456", "Ahm*** B.");

        when(customerService.lookupAccount("VGL0000123456")).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/lookup")
                        .param("account_number", "VGL0000123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_number", is("VGL0000123456")))
                .andExpect(jsonPath("$.data.display_name", is("Ahm*** B.")))
                .andExpect(jsonPath("$.data.exists", is(true)));
    }

    @Test
    @DisplayName("GET /internal/customers/{userId}/risk-profile - Succès API interne pour Risk Management")
    void shouldReturnRiskProfileInternally() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerRiskProfileResponse response = new CustomerRiskProfileResponse(
                customerId, userId, "VGL0000000001", 35, null, "Paris", "FR",
                48.8566, 2.3522, 2161000, Instant.now()
        );

        when(customerService.getRiskProfileByUserId(userId)).thenReturn(response);

        mockMvc.perform(get("/internal/customers/" + userId + "/risk-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId", is(customerId.toString())))
                .andExpect(jsonPath("$.data.age", is(35)))
                .andExpect(jsonPath("$.data.cityPopulation", is(2161000)));
    }

    @Test
    @DisplayName("POST /internal/events - Succès ingestion idempotente de USER_REGISTERED")
    void shouldIngestUserRegisteredEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserRegisteredEventPayload event = new UserRegisteredEventPayload(
                eventId, userId, "test@example.com", "Test User"
        );

        CustomerResponse response = new CustomerResponse();
        response.setUserId(userId);
        response.setFullName("Test User");
        response.setAccountNumber("VGL0000000009");

        when(customerService.createCustomerFromRegistration(any(UserRegisteredEventPayload.class), any()))
                .thenReturn(response);

        mockMvc.perform(post("/internal/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountNumber", is("VGL0000000009")));
    }

    // =========================================================================
    // Nouveaux tests de sécurité (IDOR, Rôles privilégiés et Validation)
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/customers/{id} - Échec 403 Forbidden si accès au profil d'un tiers sans rôle privilégié (Anti-IDOR)")
    void shouldReturn403WhenAccessingAnotherCustomerProfile() throws Exception {
        UUID myUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID targetCustomerId = UUID.randomUUID();

        CustomerResponse otherCustomer = new CustomerResponse();
        otherCustomer.setId(targetCustomerId);
        otherCustomer.setUserId(otherUserId);
        otherCustomer.setFullName("Target User");

        when(customerService.getProfileByCustomerId(targetCustomerId)).thenReturn(otherCustomer);

        mockMvc.perform(get("/api/v1/customers/" + targetCustomerId)
                        .header("X-User-Id", myUserId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")))
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id} - Succès 200 OK si le client consulte son propre profil par ID")
    void shouldAllowAccessWhenCustomerAccessesOwnProfile() throws Exception {
        UUID myUserId = UUID.randomUUID();
        UUID myCustomerId = UUID.randomUUID();

        CustomerResponse myCustomer = new CustomerResponse();
        myCustomer.setId(myCustomerId);
        myCustomer.setUserId(myUserId);
        myCustomer.setFullName("My Self");

        when(customerService.getProfileByCustomerId(myCustomerId)).thenReturn(myCustomer);

        mockMvc.perform(get("/api/v1/customers/" + myCustomerId)
                        .header("X-User-Id", myUserId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("My Self")));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id} - Succès 200 OK si accès au profil d'un tiers par un ANALYST ou ADMIN")
    void shouldAllowAccessWhenCallerIsAnalystOrAdmin() throws Exception {
        UUID analystUserId = UUID.randomUUID();
        UUID targetCustomerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        CustomerResponse targetCustomer = new CustomerResponse();
        targetCustomer.setId(targetCustomerId);
        targetCustomer.setUserId(targetUserId);
        targetCustomer.setFullName("Investigated Customer");

        when(customerService.getProfileByCustomerId(targetCustomerId)).thenReturn(targetCustomer);

        mockMvc.perform(get("/api/v1/customers/" + targetCustomerId)
                        .header("X-User-Id", analystUserId.toString())
                        .header("X-User-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Investigated Customer")));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id}/risk-profile - Échec 403 Forbidden si client ordinaire accède aux données de risque d'un tiers")
    void shouldRejectRiskProfileAccessForRegularCustomer() throws Exception {
        UUID regularUserId = UUID.randomUUID();
        UUID targetCustomerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        CustomerRiskProfileResponse targetRisk = new CustomerRiskProfileResponse();
        targetRisk.setCustomerId(targetCustomerId);
        targetRisk.setUserId(targetUserId);

        when(customerService.getRiskProfileByCustomerId(targetCustomerId)).thenReturn(targetRisk);

        mockMvc.perform(get("/api/v1/customers/" + targetCustomerId + "/risk-profile")
                        .header("X-User-Id", regularUserId.toString())
                        .header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")))
                .andExpect(jsonPath("$.status", is(403)));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id}/risk-profile - Succès 200 OK pour rôle privilégié ANALYST")
    void shouldAllowRiskProfileAccessForAnalyst() throws Exception {
        UUID analystUserId = UUID.randomUUID();
        UUID targetCustomerId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        CustomerRiskProfileResponse targetRisk = new CustomerRiskProfileResponse();
        targetRisk.setCustomerId(targetCustomerId);
        targetRisk.setUserId(targetUserId);
        targetRisk.setAge(29);

        when(customerService.getRiskProfileByCustomerId(targetCustomerId)).thenReturn(targetRisk);

        mockMvc.perform(get("/api/v1/customers/" + targetCustomerId + "/risk-profile")
                        .header("X-User-Id", analystUserId.toString())
                        .header("X-User-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.age", is(29)));
    }

    @Test
    @DisplayName("GET /api/v1/customers/lookup - Échec 400 Bad Request si account_number absent ou invalide")
    void shouldRejectLookupWithInvalidAccountNumber() throws Exception {
        // Paramètre vide
        mockMvc.perform(get("/api/v1/customers/lookup")
                        .param("account_number", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        // Caractères invalides (minuscules, caractères spéciaux)
        mockMvc.perform(get("/api/v1/customers/lookup")
                        .param("account_number", "vgl<script>"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));

        // Trop court (< 6)
        mockMvc.perform(get("/api/v1/customers/lookup")
                        .param("account_number", "VGL01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }
}
