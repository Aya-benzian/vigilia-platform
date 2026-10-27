package com.vigilia.customer.service;

import com.vigilia.customer.dto.*;

import java.util.List;
import java.util.UUID;

public interface CustomerService {

    CustomerResponse getProfileByUserId(UUID userId);

    CustomerResponse getProfileByCustomerId(UUID customerId);

    CustomerResponse updateProfile(UUID userId, CustomerUpdateRequest request, String traceId);

    CustomerPreferenceResponse getPreferences(UUID userId);

    CustomerPreferenceResponse updatePreferences(UUID userId, CustomerPreferenceUpdateRequest request);

    List<BeneficiaryResponse> getBeneficiaries(UUID userId);

    BeneficiaryResponse addBeneficiary(UUID userId, BeneficiaryCreateRequest request);

    void deleteBeneficiary(UUID userId, UUID beneficiaryId);

    CustomerLookupResponse lookupAccount(String accountNumber);

    CustomerRiskProfileResponse getRiskProfileByCustomerId(UUID customerId);

    CustomerRiskProfileResponse getRiskProfileByUserId(UUID userId);

    CustomerResponse createCustomerFromRegistration(UserRegisteredEventPayload event, String traceId);

    CustomerResponse createCustomerDirect(CustomerCreateRequest request, String traceId);
}
