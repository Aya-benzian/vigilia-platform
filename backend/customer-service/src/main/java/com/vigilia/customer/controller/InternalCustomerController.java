package com.vigilia.customer.controller;

import com.vigilia.customer.dto.ApiResponse;
import com.vigilia.customer.dto.CustomerResponse;
import com.vigilia.customer.dto.CustomerRiskProfileResponse;
import com.vigilia.customer.dto.UserRegisteredEventPayload;
import com.vigilia.customer.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal")
@Tag(name = "Internal Customer API", description = "Internal service-to-service endpoints for Risk Management and event ingestion")
public class InternalCustomerController {

    private final CustomerService customerService;

    public InternalCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/customers/{userId}")
    @Operation(summary = "Profil client par userId", description = "Fournit les informations client (dont email) aux services internes")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomerByUserId(@PathVariable UUID userId) {
        CustomerResponse response = customerService.getProfileByUserId(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Endpoint for Risk Management to retrieve customer risk and demographic baseline features.
     */
    @GetMapping("/customers/{userId}/risk-profile")
    @Operation(summary = "Profil de risque client par userId", description = "Fournit le profil de risque et démographique pour le moteur de détection ML")
    public ResponseEntity<ApiResponse<CustomerRiskProfileResponse>> getRiskProfileByUserId(@PathVariable UUID userId) {
        CustomerRiskProfileResponse response = customerService.getRiskProfileByUserId(userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/customers/by-customer-id/{customerId}/risk-profile")
    @Operation(summary = "Profil de risque client par customerId", description = "Fournit le profil de risque et démographique par identifiant client")
    public ResponseEntity<ApiResponse<CustomerRiskProfileResponse>> getRiskProfileByCustomerId(@PathVariable UUID customerId) {
        CustomerRiskProfileResponse response = customerService.getRiskProfileByCustomerId(customerId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Ingestion endpoint for USER_REGISTERED event from Auth Service.
     * Guarantees idempotent customer profile creation and Transactional Outbox CUSTOMER_CREATED event.
     */
    @PostMapping(path = {"/customers/events", "/events"})
    @Operation(summary = "Ingestion d'événements internes", description = "Consomme de manière idempotente l'événement USER_REGISTERED pour créer le profil client")
    public ResponseEntity<ApiResponse<CustomerResponse>> ingestEvent(
            @RequestBody UserRegisteredEventPayload event,
            HttpServletRequest request) {
        String traceId = MDC.get("correlationId");
        if (traceId == null || traceId.isBlank()) {
            traceId = event.getTraceId();
        }

        CustomerResponse response = customerService.createCustomerFromRegistration(event, traceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
