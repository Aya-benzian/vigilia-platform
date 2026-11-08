package com.vigilia.auth.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.entity.LoginAttemptEntity;
import com.vigilia.auth.entity.OutboxEventEntity;
import com.vigilia.auth.entity.UserEntity;
import com.vigilia.auth.repository.LoginAttemptRepository;
import com.vigilia.auth.repository.OutboxEventRepository;
import com.vigilia.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext
@TestPropertySource(properties = {
        "auth.outbox.relay.enabled=false"
})
@DisplayName("Auth Service - Tests d'intégration HTTP complets (Controller -> Service -> BCrypt -> DB -> Outbox)")
class AuthSecurityEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private LoginAttemptRepository loginAttemptRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanUp() {
        loginAttemptRepository.deleteAll();
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("HTTP Register réel : HTTP 201, hachage BCrypt en base et événement Outbox atomique")
    void registerHttp_realEndToEnd_createsUserWithBcryptAndOutbox() throws Exception {
        String email = "sarah.connor@example.com";
        String password = "Terminator2026!Secure";

        RegisterRequest request = new RegisterRequest(email, password);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-reg-101")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());

        // 1. Vérification en base réelle
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo(password);
        assertThat(user.getPasswordHash()).startsWith("$2a$12$");
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();

        // 2. Vérification de l'Outbox dans la même transaction
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        assertThat(events).isNotEmpty();
        OutboxEventEntity registeredEvent = events.stream()
                .filter(e -> "USER_REGISTERED".equals(e.getEventType()) && e.getPayload().contains(email))
                .findFirst()
                .orElseThrow();
        assertThat(registeredEvent.getTraceId()).isEqualTo("corr-reg-101");
        assertThat(registeredEvent.getPayload()).contains(email);
    }

    @Test
    @DisplayName("HTTP Anti-Bruteforce réel : 5 échecs successifs -> verrouillage du compte (HTTP 403 ACCOUNT_LOCKED)")
    void loginHttp_antiBruteforce_locksAccountAfterFiveFailures() throws Exception {
        String email = "victim.bruteforce@example.com";
        String password = "LegitPassword123!";

        // 1. Inscription initiale
        RegisterRequest reg = new RegisterRequest(email, password);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated());

        // 2. Cinq tentatives consécutives avec mot de passe erroné (doivent échouer en 401 INVALID_CREDENTIALS)
        for (int i = 1; i <= 5; i++) {
            LoginRequest badLogin = new LoginRequest(email, "WrongPassword" + i);
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Correlation-Id", "corr-bad-" + i)
                            .content(objectMapper.writeValueAsString(badLogin)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        // 3. Sixième tentative (même avec le bon mot de passe !) -> bloquée en 403 ACCOUNT_LOCKED
        LoginRequest sixthAttempt = new LoginRequest(email, password);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-lockout-check")
                        .content(objectMapper.writeValueAsString(sixthAttempt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // 4. Vérification de l'état de verrouillage en base de données
        UserEntity lockedUser = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(lockedUser.getLockedUntil()).isNotNull();

        // 5. Vérification du journal d'audit des tentatives
        List<LoginAttemptEntity> attempts = loginAttemptRepository.findAll();
        assertThat(attempts).hasSize(6); // 5 échecs + 1 tentative sur compte bloqué
        assertThat(attempts.stream().allMatch(a -> !a.getWasSuccessful())).isTrue();
    }
}
