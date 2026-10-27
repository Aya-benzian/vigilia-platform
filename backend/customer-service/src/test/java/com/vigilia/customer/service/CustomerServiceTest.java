package com.vigilia.customer.service;

import com.vigilia.customer.dto.*;
import com.vigilia.customer.entity.BeneficiaryEntity;
import com.vigilia.customer.entity.CustomerEntity;
import com.vigilia.customer.entity.CustomerPreferenceEntity;
import com.vigilia.customer.entity.ProcessedEventEntity;
import com.vigilia.customer.exception.DuplicateResourceException;
import com.vigilia.customer.exception.InvalidRequestException;
import com.vigilia.customer.exception.ResourceNotFoundException;
import com.vigilia.customer.repository.BeneficiaryRepository;
import com.vigilia.customer.repository.CustomerPreferenceRepository;
import com.vigilia.customer.repository.CustomerRepository;
import com.vigilia.customer.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerPreferenceRepository preferenceRepository;

    @Mock
    private BeneficiaryRepository beneficiaryRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private CustomerOutboxService outboxService;

    private CustomerServiceImpl customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(
                customerRepository,
                preferenceRepository,
                beneficiaryRepository,
                processedEventRepository,
                outboxService
        );
    }

    // =========================================================================
    // Objectif 1 : Création automatique et idempotente du profil client
    // =========================================================================

    @Test
    @DisplayName("Objectif 1 - Création automatique réussie du profil après USER_REGISTERED")
    void shouldCreateCustomerProfileFromUserRegisteredEvent() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String traceId = "test-trace-123";

        UserRegisteredEventPayload event = new UserRegisteredEventPayload(
                eventId,
                userId,
                "ahmed.benali@example.com",
                "Ahmed Benali"
        );
        event.setDateOfBirth(LocalDate.of(1992, 5, 20));
        event.setHomeCity("Lyon");
        event.setHomeCountry("FR");
        event.setHomeLat(new BigDecimal("45.764043"));
        event.setHomeLon(new BigDecimal("4.835659"));
        event.setCityPopulation(522000);

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(customerRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(customerRepository.save(any(CustomerEntity.class))).thenAnswer(invocation -> {
            CustomerEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        CustomerResponse response = customerService.createCustomerFromRegistration(event, traceId);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getFullName()).isEqualTo("Ahmed Benali");
        assertThat(response.getHomeCity()).isEqualTo("Lyon");
        assertThat(response.getAccountNumber()).startsWith("VGL");

        // Vérifications persistance et outbox
        verify(customerRepository).save(any(CustomerEntity.class));
        verify(preferenceRepository).save(any(CustomerPreferenceEntity.class));
        verify(outboxService).publishCustomerCreated(any(CustomerEntity.class), eq(traceId));
        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    }

    @Test
    @DisplayName("Objectif 1 - Idempotence : Ne pas recréer de profil si l'événement a déjà été traité")
    void shouldBeIdempotentWhenEventAlreadyProcessed() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String traceId = "trace-dup-1";

        UserRegisteredEventPayload event = new UserRegisteredEventPayload(eventId, userId, "dup@example.com", "Dup User");

        CustomerEntity existing = new CustomerEntity(
                userId, "Dup User", LocalDate.of(1990, 1, 1),
                "Paris", "FR", new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                2161000, "VGL0000000001"
        );
        existing.setId(UUID.randomUUID());

        when(processedEventRepository.existsById(eventId)).thenReturn(true);
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        CustomerResponse response = customerService.createCustomerFromRegistration(event, traceId);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getAccountNumber()).isEqualTo("VGL0000000001");

        // Aucune nouvelle insertion ni publication outbox
        verify(customerRepository, never()).save(any());
        verify(outboxService, never()).publishCustomerCreated(any(), any());
    }

    @Test
    @DisplayName("Objectif 1 - Idempotence : Ne pas recréer si le userId a déjà un profil client")
    void shouldBeIdempotentWhenCustomerAlreadyExistsForUserId() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        UserRegisteredEventPayload event = new UserRegisteredEventPayload(eventId, userId, "user@example.com", "Existing User");

        CustomerEntity existing = new CustomerEntity(
                userId, "Existing User", LocalDate.of(1990, 1, 1),
                "Paris", "FR", new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                2161000, "VGL0000000002"
        );
        existing.setId(UUID.randomUUID());

        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        CustomerResponse response = customerService.createCustomerFromRegistration(event, "trace-1");

        assertThat(response.getAccountNumber()).isEqualTo("VGL0000000002");
        verify(customerRepository, never()).save(any());
        verify(processedEventRepository).save(any(ProcessedEventEntity.class));
    }

    // =========================================================================
    // Objectif 2 : Gestion du profil client, préférences et bénéficiaires
    // =========================================================================

    @Test
    @DisplayName("Objectif 2 - Consultation du profil client par userId")
    void shouldGetProfileByUserId() {
        UUID userId = UUID.randomUUID();
        CustomerEntity customer = new CustomerEntity(
                userId, "Claire Martin", LocalDate.of(1988, 3, 14),
                "Marseille", "FR", new BigDecimal("43.296482"), new BigDecimal("5.369780"),
                870000, "VGL0000000003"
        );
        customer.setId(UUID.randomUUID());

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getProfileByUserId(userId);

        assertThat(response).isNotNull();
        assertThat(response.getFullName()).isEqualTo("Claire Martin");
        assertThat(response.getHomeCity()).isEqualTo("Marseille");
        assertThat(response.getAccountNumber()).isEqualTo("VGL0000000003");
    }

    @Test
    @DisplayName("Objectif 2 - Erreur 404 si le profil client n'existe pas")
    void shouldThrowNotFoundWhenProfileDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(customerRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getProfileByUserId(userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Profil client introuvable");
    }

    @Test
    @DisplayName("Objectif 2 - Mise à jour du profil client et émission CUSTOMER_UPDATED")
    void shouldUpdateProfileAndEmitOutboxEvent() {
        UUID userId = UUID.randomUUID();
        CustomerEntity customer = new CustomerEntity(
                userId, "Old Name", LocalDate.of(1990, 1, 1),
                "Paris", "FR", new BigDecimal("48.8566"), new BigDecimal("2.3522"),
                2161000, "VGL0000000004"
        );
        customer.setId(UUID.randomUUID());

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(CustomerEntity.class))).thenAnswer(i -> i.getArgument(0));

        CustomerUpdateRequest updateRequest = new CustomerUpdateRequest();
        updateRequest.setFullName("New Name");
        updateRequest.setHomeCity("Bordeaux");
        updateRequest.setHomeCountry("FR");

        CustomerResponse response = customerService.updateProfile(userId, updateRequest, "trace-upd-1");

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getHomeCity()).isEqualTo("Bordeaux");
        verify(outboxService).publishCustomerUpdated(any(CustomerEntity.class), eq("trace-upd-1"));
    }

    @Test
    @DisplayName("Objectif 2 - Consultation et mise à jour des préférences")
    void shouldGetAndUpdatePreferences() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        customer.setUserId(userId);

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(customer));
        when(preferenceRepository.findByCustomerId(customerId)).thenReturn(Optional.of(
                new CustomerPreferenceEntity(customerId, true, true, "en")
        ));
        when(preferenceRepository.save(any(CustomerPreferenceEntity.class))).thenAnswer(i -> i.getArgument(0));

        // Consultation
        CustomerPreferenceResponse prefResponse = customerService.getPreferences(userId);
        assertThat(prefResponse.getEmailNotifications()).isTrue();
        assertThat(prefResponse.getLanguage()).isEqualTo("en");

        // Mise à jour
        CustomerPreferenceUpdateRequest updateReq = new CustomerPreferenceUpdateRequest(false, true, "fr");
        CustomerPreferenceResponse updatedPref = customerService.updatePreferences(userId, updateReq);

        assertThat(updatedPref.getEmailNotifications()).isFalse();
        assertThat(updatedPref.getLanguage()).isEqualTo("fr");
    }

    @Test
    @DisplayName("Objectif 2 - Ajout de bénéficiaire réussi")
    void shouldAddBeneficiarySuccessfully() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CustomerEntity current = new CustomerEntity();
        current.setId(customerId);
        current.setUserId(userId);
        current.setAccountNumber("VGL0000000010");

        UUID recipientId = UUID.randomUUID();
        CustomerEntity recipient = new CustomerEntity();
        recipient.setId(recipientId);
        recipient.setAccountNumber("VGL0000000020");

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(current));
        when(customerRepository.findByAccountNumber("VGL0000000020")).thenReturn(Optional.of(recipient));
        when(beneficiaryRepository.existsByCustomerIdAndRecipientAccountNumber(customerId, "VGL0000000020")).thenReturn(false);
        when(beneficiaryRepository.save(any(BeneficiaryEntity.class))).thenAnswer(i -> {
            BeneficiaryEntity b = i.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        BeneficiaryCreateRequest req = new BeneficiaryCreateRequest("VGL0000000020", "My Friend");
        BeneficiaryResponse response = customerService.addBeneficiary(userId, req);

        assertThat(response).isNotNull();
        assertThat(response.getRecipientAccountNumber()).isEqualTo("VGL0000000020");
        assertThat(response.getNickname()).isEqualTo("My Friend");
    }

    @Test
    @DisplayName("Objectif 2 - Rejet si ajout de son propre compte comme bénéficiaire")
    void shouldRejectAddingSelfAsBeneficiary() {
        UUID userId = UUID.randomUUID();
        CustomerEntity current = new CustomerEntity();
        current.setId(UUID.randomUUID());
        current.setUserId(userId);
        current.setAccountNumber("VGL0000000010");

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(current));

        BeneficiaryCreateRequest req = new BeneficiaryCreateRequest("VGL0000000010", "Self");

        assertThatThrownBy(() -> customerService.addBeneficiary(userId, req))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Impossible de vous ajouter vous-même");
    }

    @Test
    @DisplayName("Objectif 2 - Rejet si compte bénéficiaire inexistant")
    void shouldRejectAddingNonExistentBeneficiary() {
        UUID userId = UUID.randomUUID();
        CustomerEntity current = new CustomerEntity();
        current.setId(UUID.randomUUID());
        current.setUserId(userId);
        current.setAccountNumber("VGL0000000010");

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(current));
        when(customerRepository.findByAccountNumber("VGL9999999999")).thenReturn(Optional.empty());

        BeneficiaryCreateRequest req = new BeneficiaryCreateRequest("VGL9999999999", "Unknown");

        assertThatThrownBy(() -> customerService.addBeneficiary(userId, req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("n'existe pas");
    }

    @Test
    @DisplayName("Objectif 2 - Rejet si bénéficiaire déjà enregistré (409 Conflit)")
    void shouldRejectAddingDuplicateBeneficiary() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CustomerEntity current = new CustomerEntity();
        current.setId(customerId);
        current.setUserId(userId);
        current.setAccountNumber("VGL0000000010");

        CustomerEntity recipient = new CustomerEntity();
        recipient.setId(UUID.randomUUID());
        recipient.setAccountNumber("VGL0000000020");

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(current));
        when(customerRepository.findByAccountNumber("VGL0000000020")).thenReturn(Optional.of(recipient));
        when(beneficiaryRepository.existsByCustomerIdAndRecipientAccountNumber(customerId, "VGL0000000020")).thenReturn(true);

        BeneficiaryCreateRequest req = new BeneficiaryCreateRequest("VGL0000000020", "Duplicate");

        assertThatThrownBy(() -> customerService.addBeneficiary(userId, req))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("déjà enregistré");
    }

    @Test
    @DisplayName("Objectif 2 - Suppression de bénéficiaire et vérification d'appartenance")
    void shouldDeleteBeneficiarySuccessfully() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID beneficiaryId = UUID.randomUUID();

        CustomerEntity current = new CustomerEntity();
        current.setId(customerId);
        current.setUserId(userId);

        BeneficiaryEntity beneficiary = new BeneficiaryEntity();
        beneficiary.setId(beneficiaryId);
        beneficiary.setCustomerId(customerId);

        when(customerRepository.findByUserId(userId)).thenReturn(Optional.of(current));
        when(beneficiaryRepository.findByIdAndCustomerId(beneficiaryId, customerId)).thenReturn(Optional.of(beneficiary));

        customerService.deleteBeneficiary(userId, beneficiaryId);

        verify(beneficiaryRepository).delete(beneficiary);
    }

    @Test
    @DisplayName("Objectif 2 - Recherche de compte avec masquage du nom (Anonymisation)")
    void shouldLookupAccountAndMaskDisplayName() {
        CustomerEntity customer = new CustomerEntity();
        customer.setAccountNumber("VGL0000123456");
        customer.setFullName("Ahmed Benali");

        when(customerRepository.findByAccountNumber("VGL0000123456")).thenReturn(Optional.of(customer));

        CustomerLookupResponse response = customerService.lookupAccount("VGL0000123456");

        assertThat(response.isExists()).isTrue();
        assertThat(response.getAccountNumber()).isEqualTo("VGL0000123456");
        assertThat(response.getDisplayName()).isEqualTo("Ahm*** B.");
    }

    @Test
    @DisplayName("Objectif 2 - Recherche de compte inexistant retourne exists = false")
    void shouldReturnNotExistsForUnknownAccount() {
        when(customerRepository.findByAccountNumber("VGL9999999999")).thenReturn(Optional.empty());

        CustomerLookupResponse response = customerService.lookupAccount("VGL9999999999");

        assertThat(response.isExists()).isFalse();
        assertThat(response.getDisplayName()).isNull();
    }

    // =========================================================================
    // Objectif 3 : API de données comportementales pour Risk Management
    // =========================================================================

    @Test
    @DisplayName("Objectif 3 - Calcul et restitution du profil de risque client")
    void shouldBuildRiskProfileCorrectly() {
        UUID customerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        CustomerEntity customer = new CustomerEntity(
                userId,
                "Sophie Laurent",
                LocalDate.now().minusYears(32),
                "Nantes",
                "FR",
                new BigDecimal("47.218371"),
                new BigDecimal("-1.553621"),
                318000,
                "VGL0000000099"
        );
        customer.setId(customerId);

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        CustomerRiskProfileResponse riskProfile = customerService.getRiskProfileByCustomerId(customerId);

        assertThat(riskProfile).isNotNull();
        assertThat(riskProfile.getCustomerId()).isEqualTo(customerId);
        assertThat(riskProfile.getAge()).isEqualTo(32);
        assertThat(riskProfile.getHomeCity()).isEqualTo("Nantes");
        assertThat(riskProfile.getHomeCountry()).isEqualTo("FR");
        assertThat(riskProfile.getLatitude()).isEqualTo(47.218371);
        assertThat(riskProfile.getLongitude()).isEqualTo(-1.553621);
        assertThat(riskProfile.getCityPopulation()).isEqualTo(318000);
    }
}
