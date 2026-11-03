package com.vigilia.auth.integration;

import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.entity.LoginAttemptEntity;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.entity.UserEntity;
import com.vigilia.auth.exception.AccountLockedException;
import com.vigilia.auth.exception.AuthenticationFailedException;
import com.vigilia.auth.exception.UserAlreadyExistsException;
import com.vigilia.auth.repository.LoginAttemptRepository;
import com.vigilia.auth.repository.OutboxEventRepository;
import com.vigilia.auth.repository.UserRepository;
import com.vigilia.auth.security.JwtTokenProvider;
import com.vigilia.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Auth Service - Tests d'intégration réels avec PostgreSQL et Flyway")
class AuthPostgresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vigilia_test")
            .withUsername("vigilia")
            .withPassword("vigilia_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "?currentSchema=auth");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "auth");
        registry.add("spring.flyway.default-schema", () -> "auth");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
        registry.add("jwt.issuer", () -> "vigilia-auth");
        registry.add("jwt.expiration", () -> "900000");
        registry.add("jwt.refresh-expiration-days", () -> "7");
        registry.add("jwt.kid", () -> "vigilia-key-1");
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void cleanUp() {
        loginAttemptRepository.deleteAll();
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Flyway et Hibernate : Démarrage réussi, migrations exécutées et schéma validé")
    void testFlywayMigrationsAndHibernateValidation() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(userRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("2. Inscription réelle : Persistance PostgreSQL, mot de passe haché BCrypt et événement Outbox atomique")
    void testRealRegistrationWithBcryptAndOutbox() {
        String email = "claire.bernard@example.com";
        String rawPassword = "StrongPassword123!";

        RegisterRequest request = new RegisterRequest(email, rawPassword);
        AuthResponse response = authService.register(request, "192.168.1.50", "trace-auth-001");

        assertThat(response).isNotNull();
        assertThat(response.getEmail()).isEqualTo(email);
        assertThat(response.getRole()).isEqualTo("CUSTOMER");
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(response.getAccessToken())).isTrue();

        // Vérification de la persistance réelle dans PostgreSQL
        Optional<UserEntity> savedUserOpt = userRepository.findByEmailIgnoreCase(email);
        assertThat(savedUserOpt).isPresent();
        UserEntity savedUser = savedUserOpt.get();

        // Le mot de passe stocké ne doit jamais être en clair et doit être vérifiable via BCrypt
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(savedUser.getPasswordHash()).startsWith("$2a$12$");
        assertThat(passwordEncoder.matches(rawPassword, savedUser.getPasswordHash())).isTrue();

        // Vérification de l'événement Outbox dans la même transaction
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        OutboxEventEntity event = outboxEvents.get(0);
        assertThat(event.getAggregateType()).isEqualTo("USER");
        assertThat(event.getEventType()).isEqualTo("USER_REGISTERED");
        assertThat(event.getTraceId()).isEqualTo("trace-auth-001");
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getPayload()).contains(email);
    }

    @Test
    @DisplayName("3. Unicité email : L'inscription d'un email existant lève UserAlreadyExistsException et n'altère pas la base")
    void testDuplicateRegistrationFails() {
        String email = "duplicate.test@example.com";
        RegisterRequest request = new RegisterRequest(email, "PasswordOne123!");
        authService.register(request, "127.0.0.1", "trace-1");

        RegisterRequest duplicateRequest = new RegisterRequest(email, "PasswordTwo456!");
        assertThatThrownBy(() -> authService.register(duplicateRequest, "127.0.0.1", "trace-2"))
                .isInstanceOf(UserAlreadyExistsException.class);

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Connexion réelle : Succès avec jeton JWT RS256 et échec avec enregistrement d'audit")
    void testRealLoginSuccessAndFailureAudit() {
        String email = "lucas.dupont@example.com";
        String rawPassword = "CorrectPassword456!";
        authService.register(new RegisterRequest(email, rawPassword), "127.0.0.1", "trace-reg");

        // Connexion réussie
        LoginRequest loginRequest = new LoginRequest(email, rawPassword);
        AuthResponse loginResponse = authService.login(loginRequest, "192.168.1.100", "trace-login");
        assertThat(loginResponse).isNotNull();
        assertThat(loginResponse.getAccessToken()).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(loginResponse.getAccessToken())).isTrue();

        // Connexion avec mot de passe erroné
        LoginRequest badLoginRequest = new LoginRequest(email, "WrongPassword!");
        assertThatThrownBy(() -> authService.login(badLoginRequest, "192.168.1.100", "trace-bad-login"))
                .isInstanceOf(AuthenticationFailedException.class);

        // Audit de tentative échouée enregistré
        List<LoginAttemptEntity> attempts = loginAttemptRepository.findAll();
        assertThat(attempts).isNotEmpty();
        assertThat(attempts.get(0).getEmail()).isEqualTo(email);
        assertThat(attempts.get(0).getWasSuccessful()).isFalse();
    }

    @Test
    @DisplayName("5. Protection Anti-Bruteforce : Verrouillage du compte après 5 échecs consécutifs")
    void testAntiBruteforceLockoutAfterFiveFailures() {
        String email = "target.bruteforce@example.com";
        String correctPassword = "CorrectPassword789!";
        authService.register(new RegisterRequest(email, correctPassword), "127.0.0.1", "trace-reg");

        // Simuler 5 échecs consécutifs
        for (int i = 1; i <= 5; i++) {
            int attempt = i;
            LoginRequest badRequest = new LoginRequest(email, "WrongAttempt" + attempt);
            assertThatThrownBy(() -> authService.login(badRequest, "10.0.0.1", "trace-attempt-" + attempt))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        // Le 6ème essai doit déclencher un AccountLockedException
        LoginRequest sixthAttempt = new LoginRequest(email, correctPassword);
        assertThatThrownBy(() -> authService.login(sixthAttempt, "10.0.0.1", "trace-attempt-6"))
                .isInstanceOf(AccountLockedException.class);

        // Vérifier dans PostgreSQL que l'utilisateur est bien verrouillé
        UserEntity lockedUser = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(lockedUser.getLockedUntil()).isNotNull();
    }
}
