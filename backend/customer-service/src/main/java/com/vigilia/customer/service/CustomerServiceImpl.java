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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ACCOUNT_NUMBER_PREFIX = "VGL";

    private final CustomerRepository customerRepository;
    private final CustomerPreferenceRepository preferenceRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final CustomerOutboxService outboxService;

    public CustomerServiceImpl(CustomerRepository customerRepository,
                               CustomerPreferenceRepository preferenceRepository,
                               BeneficiaryRepository beneficiaryRepository,
                               ProcessedEventRepository processedEventRepository,
                               CustomerOutboxService outboxService) {
        this.customerRepository = customerRepository;
        this.preferenceRepository = preferenceRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getProfileByUserId(UUID userId) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));
        return mapToCustomerResponse(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getProfileByCustomerId(UUID customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable avec l'identifiant " + customerId));
        return mapToCustomerResponse(customer);
    }

    @Override
    @Transactional
    public CustomerResponse updateProfile(UUID userId, CustomerUpdateRequest request, String traceId) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            customer.setFullName(request.getFullName().trim());
        }
        if (request.getHomeCity() != null && !request.getHomeCity().isBlank()) {
            customer.setHomeCity(request.getHomeCity().trim());
        }
        if (request.getHomeCountry() != null && !request.getHomeCountry().isBlank()) {
            customer.setHomeCountry(request.getHomeCountry().trim().toUpperCase());
        }
        if (request.getHomeLat() != null) {
            customer.setHomeLat(request.getHomeLat());
        }
        if (request.getHomeLon() != null) {
            customer.setHomeLon(request.getHomeLon());
        }
        if (request.getCityPopulation() != null) {
            customer.setCityPopulation(request.getCityPopulation());
        }

        customer.setUpdatedAt(Instant.now());
        CustomerEntity updated = customerRepository.save(customer);

        outboxService.publishCustomerUpdated(updated, traceId);

        return mapToCustomerResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerPreferenceResponse getPreferences(UUID userId) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        CustomerPreferenceEntity preference = preferenceRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> new CustomerPreferenceEntity(customer.getId()));

        return mapToPreferenceResponse(preference);
    }

    @Override
    @Transactional
    public CustomerPreferenceResponse updatePreferences(UUID userId, CustomerPreferenceUpdateRequest request) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        CustomerPreferenceEntity preference = preferenceRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> new CustomerPreferenceEntity(customer.getId()));

        if (request.getEmailNotifications() != null) {
            preference.setEmailNotifications(request.getEmailNotifications());
        }
        if (request.getInAppNotifications() != null) {
            preference.setInAppNotifications(request.getInAppNotifications());
        }
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            preference.setLanguage(request.getLanguage().trim().toLowerCase());
        }

        preference.setUpdatedAt(Instant.now());
        CustomerPreferenceEntity saved = preferenceRepository.save(preference);

        return mapToPreferenceResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> getBeneficiaries(UUID userId) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        return beneficiaryRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId())
                .stream()
                .map(this::mapToBeneficiaryResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BeneficiaryResponse addBeneficiary(UUID userId, BeneficiaryCreateRequest request) {
        CustomerEntity currentCustomer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        String recipientAccountNumber = request.getRecipientAccountNumber().trim();

        // 1. Interdiction de s'ajouter soi-même en bénéficiaire
        if (currentCustomer.getAccountNumber().equalsIgnoreCase(recipientAccountNumber)) {
            throw new InvalidRequestException("Impossible de vous ajouter vous-même comme bénéficiaire");
        }

        // 2. Vérification de l'existence du compte destinataire
        CustomerEntity recipientCustomer = customerRepository.findByAccountNumber(recipientAccountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Le compte bénéficiaire " + recipientAccountNumber + " n'existe pas"));

        // 3. Vérification de l'unicité du bénéficiaire pour ce client
        if (beneficiaryRepository.existsByCustomerIdAndRecipientAccountNumber(currentCustomer.getId(), recipientAccountNumber)) {
            throw new DuplicateResourceException("Ce bénéficiaire est déjà enregistré pour votre compte");
        }

        BeneficiaryEntity beneficiary = new BeneficiaryEntity(
                currentCustomer.getId(),
                recipientCustomer.getId(),
                recipientAccountNumber,
                request.getNickname() != null ? request.getNickname().trim() : null
        );

        BeneficiaryEntity saved = beneficiaryRepository.save(beneficiary);
        log.info("Nouveau bénéficiaire enregistré [customerId={}, recipientAccount={}]",
                currentCustomer.getId(), recipientAccountNumber);

        return mapToBeneficiaryResponse(saved);
    }

    @Override
    @Transactional
    public void deleteBeneficiary(UUID userId, UUID beneficiaryId) {
        CustomerEntity currentCustomer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour cet utilisateur"));

        BeneficiaryEntity beneficiary = beneficiaryRepository.findByIdAndCustomerId(beneficiaryId, currentCustomer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Bénéficiaire introuvable ou non associé à votre compte"));

        beneficiaryRepository.delete(beneficiary);
        log.info("Bénéficiaire supprimé [beneficiaryId={}, customerId={}]", beneficiaryId, currentCustomer.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerLookupResponse lookupAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return CustomerLookupResponse.notFound(accountNumber);
        }

        Optional<CustomerEntity> customerOpt = customerRepository.findByAccountNumber(accountNumber.trim());
        if (customerOpt.isEmpty()) {
            return CustomerLookupResponse.notFound(accountNumber.trim());
        }

        CustomerEntity customer = customerOpt.get();
        String maskedDisplayName = maskFullName(customer.getFullName());

        return CustomerLookupResponse.found(customer.getAccountNumber(), maskedDisplayName);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerRiskProfileResponse getRiskProfileByCustomerId(UUID customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable avec l'identifiant " + customerId));
        return buildRiskProfile(customer);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerRiskProfileResponse getRiskProfileByUserId(UUID userId) {
        CustomerEntity customer = customerRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour l'utilisateur " + userId));
        return buildRiskProfile(customer);
    }

    @Override
    @Transactional
    public CustomerResponse createCustomerFromRegistration(UserRegisteredEventPayload event, String traceId) {
        UUID eventId = event.getEventId();

        // 1. Idempotency Check via processed_events
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            log.info("Événement USER_REGISTERED déjà traité [eventId={}]. Opération idempotente ignorée.", eventId);
            return customerRepository.findByUserId(event.getUserId())
                    .map(this::mapToCustomerResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("Profil client introuvable pour l'événement déjà traité"));
        }

        // 2. Vérification si le client existe déjà pour ce userId
        Optional<CustomerEntity> existingCustomer = customerRepository.findByUserId(event.getUserId());
        if (existingCustomer.isPresent()) {
            log.warn("Un profil client existe déjà pour le userId {}. Retour du profil existant.", event.getUserId());
            if (eventId != null) {
                processedEventRepository.save(new ProcessedEventEntity(eventId, "USER_REGISTERED"));
            }
            return mapToCustomerResponse(existingCustomer.get());
        }

        // 3. Préparation des données par défaut ou transmises
        String fullName = event.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = extractNameFromEmail(event.getEmail());
        }

        LocalDate dateOfBirth = event.getDateOfBirth() != null ? event.getDateOfBirth() : LocalDate.of(1995, 1, 1);
        String homeCity = event.getHomeCity() != null ? event.getHomeCity() : "Paris";
        String homeCountry = event.getHomeCountry() != null ? event.getHomeCountry().toUpperCase() : "FR";
        BigDecimal homeLat = event.getHomeLat() != null ? event.getHomeLat() : new BigDecimal("48.856614");
        BigDecimal homeLon = event.getHomeLon() != null ? event.getHomeLon() : new BigDecimal("2.352222");
        Integer cityPopulation = event.getCityPopulation() != null ? event.getCityPopulation() : 2161000;
        String accountNumber = generateUniqueAccountNumber();

        CustomerEntity customer = new CustomerEntity(
                event.getUserId(),
                fullName,
                dateOfBirth,
                homeCity,
                homeCountry,
                homeLat,
                homeLon,
                cityPopulation,
                accountNumber
        );

        CustomerEntity savedCustomer = customerRepository.save(customer);

        // 4. Initialisation des préférences par défaut
        CustomerPreferenceEntity defaultPreferences = new CustomerPreferenceEntity(savedCustomer.getId());
        preferenceRepository.save(defaultPreferences);

        // 5. Transactional Outbox : émission de CUSTOMER_CREATED
        outboxService.publishCustomerCreated(savedCustomer, traceId);

        // 6. Enregistrement de l'événement traité pour l'idempotence
        if (eventId != null) {
            processedEventRepository.save(new ProcessedEventEntity(eventId, "USER_REGISTERED"));
        }

        log.info("Profil client créé avec succès [customerId={}, userId={}, accountNumber={}]",
                savedCustomer.getId(), savedCustomer.getUserId(), savedCustomer.getAccountNumber());

        return mapToCustomerResponse(savedCustomer);
    }

    @Override
    @Transactional
    public CustomerResponse createCustomerDirect(CustomerCreateRequest request, String traceId) {
        if (request.getUserId() == null) {
            throw new InvalidRequestException("userId est obligatoire");
        }

        if (customerRepository.existsByUserId(request.getUserId())) {
            throw new DuplicateResourceException("Un profil client existe déjà pour cet utilisateur");
        }

        String fullName = request.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = extractNameFromEmail(request.getEmail());
        }

        LocalDate dateOfBirth = request.getDateOfBirth() != null ? request.getDateOfBirth() : LocalDate.of(1995, 1, 1);
        String homeCity = request.getHomeCity() != null ? request.getHomeCity() : "Paris";
        String homeCountry = request.getHomeCountry() != null ? request.getHomeCountry().toUpperCase() : "FR";
        BigDecimal homeLat = request.getHomeLat() != null ? request.getHomeLat() : new BigDecimal("48.856614");
        BigDecimal homeLon = request.getHomeLon() != null ? request.getHomeLon() : new BigDecimal("2.352222");
        Integer cityPopulation = request.getCityPopulation() != null ? request.getCityPopulation() : 2161000;
        String accountNumber = request.getAccountNumber() != null && !request.getAccountNumber().isBlank()
                ? request.getAccountNumber()
                : generateUniqueAccountNumber();

        CustomerEntity customer = new CustomerEntity(
                request.getUserId(),
                fullName,
                dateOfBirth,
                homeCity,
                homeCountry,
                homeLat,
                homeLon,
                cityPopulation,
                accountNumber
        );

        CustomerEntity savedCustomer = customerRepository.save(customer);

        CustomerPreferenceEntity defaultPreferences = new CustomerPreferenceEntity(savedCustomer.getId());
        preferenceRepository.save(defaultPreferences);

        outboxService.publishCustomerCreated(savedCustomer, traceId);

        return mapToCustomerResponse(savedCustomer);
    }

    private CustomerRiskProfileResponse buildRiskProfile(CustomerEntity customer) {
        int age = Period.between(customer.getDateOfBirth(), LocalDate.now()).getYears();

        return new CustomerRiskProfileResponse(
                customer.getId(),
                customer.getUserId(),
                customer.getAccountNumber(),
                age,
                null, // Gender is not part of official schema
                customer.getHomeCity(),
                customer.getHomeCountry(),
                customer.getHomeLat().doubleValue(),
                customer.getHomeLon().doubleValue(),
                customer.getCityPopulation(),
                customer.getCreatedAt()
        );
    }

    private String generateUniqueAccountNumber() {
        for (int i = 0; i < 20; i++) {
            long number = Math.abs(RANDOM.nextLong()) % 1_000_000_000_0L;
            String generated = String.format("%s%010d", ACCOUNT_NUMBER_PREFIX, number);
            if (!customerRepository.existsByAccountNumber(generated)) {
                return generated;
            }
        }
        return ACCOUNT_NUMBER_PREFIX + System.currentTimeMillis();
    }

    private String maskFullName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "U***";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length >= 2) {
            String first = parts[0];
            String last = parts[parts.length - 1];

            String maskedFirst = first.length() <= 3 ? first + "***" : first.substring(0, 3) + "***";
            String maskedLast = last.substring(0, 1).toUpperCase() + ".";
            return maskedFirst + " " + maskedLast;
        } else {
            String single = parts[0];
            return single.length() <= 3 ? single + "***" : single.substring(0, 3) + "***";
        }
    }

    private String extractNameFromEmail(String email) {
        if (email == null || email.isBlank()) {
            return "New Customer";
        }
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            String username = email.substring(0, atIndex).replace('.', ' ').replace('_', ' ');
            String[] tokens = username.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    sb.append(Character.toUpperCase(token.charAt(0)));
                    if (token.length() > 1) {
                        sb.append(token.substring(1).toLowerCase());
                    }
                    sb.append(" ");
                }
            }
            return sb.toString().trim();
        }
        return "New Customer";
    }

    private CustomerResponse mapToCustomerResponse(CustomerEntity customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getUserId(),
                customer.getFullName(),
                customer.getDateOfBirth(),
                customer.getHomeCity(),
                customer.getHomeCountry(),
                customer.getHomeLat(),
                customer.getHomeLon(),
                customer.getCityPopulation(),
                customer.getAccountNumber(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }

    private CustomerPreferenceResponse mapToPreferenceResponse(CustomerPreferenceEntity entity) {
        return new CustomerPreferenceResponse(
                entity.getCustomerId(),
                entity.getEmailNotifications(),
                entity.getInAppNotifications(),
                entity.getLanguage(),
                entity.getUpdatedAt()
        );
    }

    private BeneficiaryResponse mapToBeneficiaryResponse(BeneficiaryEntity entity) {
        return new BeneficiaryResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getRecipientCustomerId(),
                entity.getRecipientAccountNumber(),
                entity.getNickname(),
                entity.getCreatedAt()
        );
    }
}
