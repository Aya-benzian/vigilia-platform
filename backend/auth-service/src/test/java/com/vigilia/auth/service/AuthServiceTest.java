package com.vigilia.auth.service;

import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RefreshTokenRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.entity.LoginAttemptEntity;
import com.vigilia.auth.entity.RefreshTokenEntity;
import com.vigilia.auth.entity.UserEntity;
import com.vigilia.auth.exception.AccountLockedException;
import com.vigilia.auth.exception.AuthenticationFailedException;
import com.vigilia.auth.exception.InvalidTokenException;
import com.vigilia.auth.exception.UserAlreadyExistsException;
import com.vigilia.auth.repository.LoginAttemptRepository;
import com.vigilia.auth.repository.RefreshTokenRepository;
import com.vigilia.auth.repository.UserRepository;
import com.vigilia.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private LoginAttemptRepository loginAttemptRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private SecurityOutboxService securityOutboxService;

    private AuthServiceImpl authService;

    private UserEntity sampleUser;
    private final String rawPassword = "S3cure-Passphrase!";
    private final String encodedPassword = "$2a$12$sampleEncodedPasswordHash";

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userRepository,
                refreshTokenRepository,
                loginAttemptRepository,
                passwordEncoder,
                jwtTokenProvider,
                securityOutboxService,
                7
        );

        sampleUser = new UserEntity("sara@example.com", encodedPassword, "CUSTOMER");
        sampleUser.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Inscription normale et attribution systématique du rôle CUSTOMER")
    void testRegisterSuccess() {
        RegisterRequest request = new RegisterRequest("sara@example.com", rawPassword);
        request.setRole(null);

        when(userRepository.existsByEmailIgnoreCase("sara@example.com")).thenReturn(false);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity u = invocation.getArgument(0);
            u.setId(sampleUser.getId());
            return u;
        });
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("mocked-jwt");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        AuthResponse response = authService.register(request, "127.0.0.1", "trace-1");

        assertNotNull(response);
        assertEquals(sampleUser.getId(), response.getUserId());
        assertEquals("sara@example.com", response.getEmail());
        assertEquals("CUSTOMER", response.getRole());
        assertEquals("mocked-jwt", response.getAccessToken());
        assertNotNull(response.getRefreshToken());

        // Vérification de l'événement d'audit émis dans l'Outbox
        verify(securityOutboxService).publishSecurityEvent(
                eq("USER_REGISTERED"),
                eq(sampleUser.getId()),
                eq("sara@example.com"),
                eq("127.0.0.1"),
                eq("trace-1"),
                any()
        );
    }

    @Test
    @DisplayName("Refus strict de l'auto-attribution des rôles privilégiés ADMIN ou ANALYST")
    void testRegisterRejectPrivilegedRole() {
        RegisterRequest requestAdmin = new RegisterRequest("admin@example.com", rawPassword);
        requestAdmin.setRole("ADMIN");

        assertThrows(AccessDeniedException.class, () ->
                authService.register(requestAdmin, "127.0.0.1", "trace-admin"));

        RegisterRequest requestAnalyst = new RegisterRequest("analyst@example.com", rawPassword);
        requestAnalyst.setRole("ROLE_ANALYST");

        assertThrows(AccessDeniedException.class, () ->
                authService.register(requestAnalyst, "127.0.0.1", "trace-analyst"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Refus d'inscription si l'email existe déjà")
    void testRegisterDuplicateEmail() {
        RegisterRequest request = new RegisterRequest("sara@example.com", rawPassword);
        when(userRepository.existsByEmailIgnoreCase("sara@example.com")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () ->
                authService.register(request, "127.0.0.1", "trace-dup"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Connexion réussie avec identifiants valides et vérification BCrypt")
    void testLoginSuccess() {
        LoginRequest request = new LoginRequest("sara@example.com", rawPassword);

        when(loginAttemptRepository.countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(anyString(), any()))
                .thenReturn(0L);
        when(userRepository.findByEmailIgnoreCase("sara@example.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(sampleUser.getId(), sampleUser.getEmail(), sampleUser.getRole()))
                .thenReturn("mocked-access-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        AuthResponse response = authService.login(request, "127.0.0.1", "trace-login");

        assertNotNull(response);
        assertEquals("mocked-access-token", response.getAccessToken());
        assertNotNull(response.getRefreshToken());

        // Vérification de l'enregistrement de l'audit de succès
        verify(securityOutboxService).publishSecurityEvent(
                eq("LOGIN_SUCCEEDED"),
                eq(sampleUser.getId()),
                eq("sara@example.com"),
                eq("127.0.0.1"),
                eq("trace-login"),
                any()
        );
        verify(loginAttemptRepository).save(any(LoginAttemptEntity.class));
    }

    @Test
    @DisplayName("Échec de connexion en cas de mot de passe incorrect (message générique)")
    void testLoginBadPassword() {
        LoginRequest request = new LoginRequest("sara@example.com", "WrongPassword!");

        when(loginAttemptRepository.countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(anyString(), any()))
                .thenReturn(1L);
        when(userRepository.findByEmailIgnoreCase("sara@example.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword!", encodedPassword)).thenReturn(false);

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () ->
                authService.login(request, "127.0.0.1", "trace-fail"));

        assertEquals("Identifiants invalides", ex.getMessage());

        verify(securityOutboxService).publishSecurityEvent(
                eq("LOGIN_FAILED"),
                eq(sampleUser.getId()),
                eq("sara@example.com"),
                eq("127.0.0.1"),
                eq("trace-fail"),
                any()
        );
    }

    @Test
    @DisplayName("Absence de fuite d'informations : identifiant inconnu renvoie la même erreur générique")
    void testLoginUnknownUser() {
        LoginRequest request = new LoginRequest("unknown@example.com", rawPassword);

        when(loginAttemptRepository.countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(anyString(), any()))
                .thenReturn(0L);
        when(userRepository.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

        AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () ->
                authService.login(request, "127.0.0.1", "trace-unknown"));

        assertEquals("Identifiants invalides", ex.getMessage());
        verify(securityOutboxService).publishSecurityEvent(
                eq("LOGIN_FAILED"),
                isNull(),
                eq("unknown@example.com"),
                eq("127.0.0.1"),
                eq("trace-unknown"),
                any()
        );
    }

    @Test
    @DisplayName("Blocage temporaire après 5 tentatives infructueuses en 15 minutes")
    void testBruteForceLockoutAfter5Failures() {
        LoginRequest request = new LoginRequest("sara@example.com", rawPassword);

        when(loginAttemptRepository.countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(anyString(), any()))
                .thenReturn(5L);
        when(userRepository.findByEmailIgnoreCase("sara@example.com")).thenReturn(Optional.of(sampleUser));

        AccountLockedException ex = assertThrows(AccountLockedException.class, () ->
                authService.login(request, "127.0.0.1", "trace-lock"));

        assertTrue(ex.getMessage().contains("bloqué"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Rotation normale du refresh token : ancien token révoqué et nouveau token émis")
    void testRefreshTokenRotation() {
        UUID familyId = UUID.randomUUID();
        RefreshTokenEntity currentToken = new RefreshTokenEntity(
                sampleUser.getId(),
                familyId,
                "some-hash",
                Instant.now().plus(7, ChronoUnit.DAYS),
                "127.0.0.1",
                "Web"
        );

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(currentToken));
        when(userRepository.findById(sampleUser.getId())).thenReturn(Optional.of(sampleUser));
        when(jwtTokenProvider.generateAccessToken(sampleUser.getId(), sampleUser.getEmail(), sampleUser.getRole()))
                .thenReturn("new-access-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(900L);

        RefreshTokenRequest request = new RefreshTokenRequest("raw-refresh-token");
        AuthResponse response = authService.refreshToken(request, "127.0.0.1", "trace-refresh");

        assertNotNull(response);
        assertEquals("new-access-token", response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertTrue(currentToken.getIsRevoked());

        verify(securityOutboxService).publishSecurityEvent(
                eq("TOKEN_ROTATED"),
                eq(sampleUser.getId()),
                any(),
                eq("127.0.0.1"),
                eq("trace-refresh"),
                any()
        );
    }

    @Test
    @DisplayName("Détection de réutilisation de refresh token : révocation de la famille entière")
    void testRefreshTokenReuseDetection() {
        UUID familyId = UUID.randomUUID();
        RefreshTokenEntity revokedToken = new RefreshTokenEntity(
                sampleUser.getId(),
                familyId,
                "some-hash",
                Instant.now().plus(7, ChronoUnit.DAYS),
                "127.0.0.1",
                "Web"
        );
        revokedToken.setIsRevoked(true); // Déjà consommé !

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revokedToken));

        RefreshTokenRequest request = new RefreshTokenRequest("replayed-token");

        InvalidTokenException ex = assertThrows(InvalidTokenException.class, () ->
                authService.refreshToken(request, "127.0.0.1", "trace-reuse"));

        assertTrue(ex.getMessage().contains("réutilisation"));
        verify(refreshTokenRepository).revokeFamily(familyId);
        verify(securityOutboxService).publishSecurityEvent(
                eq("TOKEN_REUSED"),
                eq(sampleUser.getId()),
                isNull(),
                eq("127.0.0.1"),
                eq("trace-reuse"),
                any()
        );
    }

    @Test
    @DisplayName("Déconnexion : révocation du refresh token")
    void testLogoutRevokesToken() {
        RefreshTokenEntity token = new RefreshTokenEntity(
                sampleUser.getId(),
                UUID.randomUUID(),
                "some-hash",
                Instant.now().plus(7, ChronoUnit.DAYS),
                "127.0.0.1",
                "Web"
        );

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        authService.logout("raw-token", "trace-logout");

        assertTrue(token.getIsRevoked());
        verify(securityOutboxService).publishSecurityEvent(
                eq("LOGOUT"),
                eq(sampleUser.getId()),
                isNull(),
                isNull(),
                eq("trace-logout"),
                any()
        );
    }
}
