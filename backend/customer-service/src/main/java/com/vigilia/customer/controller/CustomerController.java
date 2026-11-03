package com.vigilia.customer.controller;

import com.vigilia.customer.dto.*;
import com.vigilia.customer.security.SecurityContextHelper;
import com.vigilia.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customer Management", description = "Endpoints for authenticated customer profile, preferences, and beneficiaries")
public class CustomerController {

    private final CustomerService customerService;
    private final SecurityContextHelper securityContextHelper;

    public CustomerController(CustomerService customerService, SecurityContextHelper securityContextHelper) {
        this.customerService = customerService;
        this.securityContextHelper = securityContextHelper;
    }

    // =========================================================================
    // 1. Profil client authentifié (Section 17.3 Conception v4)
    // =========================================================================

    @GetMapping("/me")
    @Operation(summary = "Consulter son profil client", description = "Récupère les informations du profil client de l'utilisateur authentifié")
    public ResponseEntity<ApiResponse<CustomerResponse>> getMyProfile(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        CustomerResponse response = customerService.getProfileByUserId(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/me")
    @Operation(summary = "Mettre à jour son profil client", description = "Met à jour les informations modifiables du profil du client connecté")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateMyProfile(
            HttpServletRequest request,
            @Valid @RequestBody CustomerUpdateRequest updateRequest) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        String traceId = MDC.get("correlationId");
        CustomerResponse response = customerService.updateProfile(userId, updateRequest, traceId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // =========================================================================
    // 2. Préférences de notification et langue
    // =========================================================================

    @GetMapping("/me/preferences")
    @Operation(summary = "Consulter ses préférences", description = "Récupère les préférences de notification et la langue du client connecté")
    public ResponseEntity<ApiResponse<CustomerPreferenceResponse>> getMyPreferences(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        CustomerPreferenceResponse response = customerService.getPreferences(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PutMapping("/me/preferences")
    @Operation(summary = "Mettre à jour ses préférences", description = "Met à jour les préférences de notification et la langue du client connecté")
    public ResponseEntity<ApiResponse<CustomerPreferenceResponse>> updateMyPreferences(
            HttpServletRequest request,
            @Valid @RequestBody CustomerPreferenceUpdateRequest preferenceRequest) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        CustomerPreferenceResponse response = customerService.updatePreferences(userId, preferenceRequest);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // =========================================================================
    // 3. Bénéficiaires enregistrés pour les virements P2P
    // =========================================================================

    @GetMapping("/me/beneficiaries")
    @Operation(summary = "Lister ses bénéficiaires", description = "Récupère la liste des destinataires approuvés pour les virements")
    public ResponseEntity<ApiResponse<List<BeneficiaryResponse>>> getMyBeneficiaries(HttpServletRequest request) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        List<BeneficiaryResponse> response = customerService.getBeneficiaries(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/me/beneficiaries")
    @Operation(summary = "Ajouter un bénéficiaire", description = "Enregistre un nouveau destinataire après vérification d'existence et unicité")
    public ResponseEntity<ApiResponse<BeneficiaryResponse>> addBeneficiary(
            HttpServletRequest request,
            @Valid @RequestBody BeneficiaryCreateRequest createRequest) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        BeneficiaryResponse response = customerService.addBeneficiary(userId, createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/me/beneficiaries/{id}")
    @Operation(summary = "Supprimer un bénéficiaire", description = "Supprime un destinataire enregistré appartenant au client authentifié")
    public ResponseEntity<ApiResponse<Void>> deleteBeneficiary(
            HttpServletRequest request,
            @PathVariable UUID id) {
        UUID userId = securityContextHelper.requireCurrentUserId(request);
        customerService.deleteBeneficiary(userId, id);
        return ResponseEntity.ok(ApiResponse.of(null, Map.of("message", "Bénéficiaire supprimé avec succès")));
    }

    private static final java.util.regex.Pattern ACCOUNT_NUMBER_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z0-9]{6,34}$");

    // =========================================================================
    // 4. Recherche de compte destinataire (Lookup avec masquage de nom)
    // =========================================================================

    @GetMapping("/lookup")
    @Operation(summary = "Recherche d'un compte destinataire", description = "Vérifie l'existence d'un compte et retourne un nom anonymisé")
    public ResponseEntity<ApiResponse<CustomerLookupResponse>> lookupAccount(
            @RequestParam(name = "account_number", required = false) String accountNumber) {

        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            throw new com.vigilia.customer.exception.InvalidRequestException("Le paramètre account_number est obligatoire");
        }

        String trimmed = accountNumber.trim();
        if (trimmed.length() < 6 || trimmed.length() > 34 || !ACCOUNT_NUMBER_PATTERN.matcher(trimmed).matches()) {
            throw new com.vigilia.customer.exception.InvalidRequestException(
                    "Numéro de compte invalide : 6 à 34 caractères alphanumériques majuscules attendus");
        }

        CustomerLookupResponse response = customerService.lookupAccount(trimmed);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // =========================================================================
    // 5. Compatibilité OpenAPI partagée (contracts/api/customer-openapi.yaml)
    // =========================================================================

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un client par ID", description = "Route de compatibilité sécurisée : propriétaire ou rôle privilégié (ANALYST/ADMIN)")
    public ResponseEntity<CustomerResponse> getCustomerById(
            @PathVariable UUID id,
            HttpServletRequest request) {

        UUID currentUserId = securityContextHelper.requireCurrentUserId(request);
        CustomerResponse response = customerService.getProfileByCustomerId(id);

        if (!currentUserId.equals(response.getUserId()) && !securityContextHelper.hasPrivilegedRole(request)) {
            throw new com.vigilia.customer.exception.ForbiddenException(
                    "Accès refusé : vous n'êtes pas autorisé à consulter le profil d'un autre client");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/risk-profile")
    @Operation(summary = "Consulter le profil de risque d'un client", description = "Route de compatibilité sécurisée pour le profil de risque ML")
    public ResponseEntity<CustomerRiskProfileResponse> getCustomerRiskProfile(
            @PathVariable UUID id,
            HttpServletRequest request) {

        UUID currentUserId = securityContextHelper.requireCurrentUserId(request);
        CustomerRiskProfileResponse response = customerService.getRiskProfileByCustomerId(id);

        if (!currentUserId.equals(response.getUserId()) && !securityContextHelper.hasPrivilegedRole(request)) {
            throw new com.vigilia.customer.exception.ForbiddenException(
                    "Accès refusé : vous n'êtes pas autorisé à consulter le profil de risque d'un autre client");
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping
    @Operation(summary = "Création directe d'un client", description = "Route de compatibilité pour création directe de profil")
    public ResponseEntity<CustomerResponse> createCustomerDirect(
            @Valid @RequestBody CustomerCreateRequest createRequest) {
        String traceId = MDC.get("correlationId");
        CustomerResponse response = customerService.createCustomerDirect(createRequest, traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
