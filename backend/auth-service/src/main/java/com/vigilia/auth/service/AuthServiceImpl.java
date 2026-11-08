package com.vigilia.auth.service;

import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RefreshTokenRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.dto.UserSummaryDto;
import com.vigilia.auth.entity.LoginAttemptEntity;
import com.vigilia.auth.entity.RefreshTokenEntity;
import com.vigilia.auth.entity.UserEntity;
import com.vigilia.auth.exception.AccountLockedException;
import com.vigilia.auth.exception.AuthenticationFailedException;
import com.vigilia.auth.exception.InvalidTokenException;
import com.vigilia.auth.exception.ResourceNotFoundException;
import com.vigilia.auth.exception.TokenExpiredException;
import com.vigilia.auth.exception.UserAlreadyExistsException;
import com.vigilia.auth.repository.LoginAttemptRepository;
import com.vigilia.auth.repository.RefreshTokenRepository;
import com.vigilia.auth.repository.UserRepository;
import com.vigilia.auth.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_WINDOW_MINUTES = 15;
    private static final int LOCKOUT_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SecurityOutboxService securityOutboxService;
    private final long refreshTokenValidityDays;

    public AuthServiceImpl(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            LoginAttemptRepository loginAttemptRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            SecurityOutboxService securityOutboxService,
            @Value("${jwt.refresh-expiration-days:7}") long refreshTokenValidityDays) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityOutboxService = securityOutboxService;
        this.refreshTokenValidityDays = refreshTokenValidityDays;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress, String traceId) {
        String email = request.getEmail().trim().toLowerCase();

        // 1. Refuser formellement toute tentative d'auto-attribution des rôles privilégiés
        if (request.getRole() != null && !request.getRole().isBlank()) {
            String requestedRole = request.getRole().trim().toUpperCase();
            if ("ADMIN".equals(requestedRole) || "ANALYST".equals(requestedRole)
                    || "ROLE_ADMIN".equals(requestedRole) || "ROLE_ANALYST".equals(requestedRole)) {
                log.warn("Tentative d'auto-attribution d'un rôle privilégié bloquée pour email={}", email);
                throw new AccessDeniedException("L'attribution de rôles privilégiés (ADMIN / ANALYST) est formellement interdite lors de l'inscription.");
            }
        }

        // 2. Vérifier l'unicité de l'identifiant
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.warn("Tentative d'inscription avec un email déjà existant: {}", email);
            throw new UserAlreadyExistsException("Un compte est déjà enregistré avec cette adresse email");
        }

        // 3. Hasher le mot de passe avec BCrypt et forcer le rôle CUSTOMER
        String passwordHash = passwordEncoder.encode(request.getPassword());
        UserEntity user = new UserEntity(email, passwordHash, "CUSTOMER");
        user = userRepository.save(user);

        // 4. Générer le refresh token opaque et le sauvegarder
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = generateOpaqueToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant refreshExpiresAt = Instant.now().plus(refreshTokenValidityDays, ChronoUnit.DAYS);

        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity(
                user.getId(),
                familyId,
                tokenHash,
                refreshExpiresAt,
                ipAddress,
                "Web Client"
        );
        refreshTokenRepository.save(refreshTokenEntity);

        // 5. Générer le jeton d'accès RS256
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());

        // 6. Émettre l'événement d'audit de sécurité
        securityOutboxService.publishSecurityEvent(
                "USER_REGISTERED",
                user.getId(),
                user.getEmail(),
                ipAddress,
                traceId,
                Map.of("role", "CUSTOMER")
        );

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getExpirationSeconds(),
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }

    @Override
    @Transactional(noRollbackFor = {AuthenticationFailedException.class, AccountLockedException.class})
    public AuthResponse login(LoginRequest request, String ipAddress, String traceId) {
        String identifier = request.resolveIdentifier().toLowerCase();
        Instant now = Instant.now();
        Instant windowStart = now.minus(LOCKOUT_WINDOW_MINUTES, ChronoUnit.MINUTES);

        // 1. Contrôle préalable de force brute (dernières 15 minutes)
        long failedAttempts = loginAttemptRepository.countByEmailIgnoreCaseAndWasSuccessfulFalseAndAttemptedAtAfter(identifier, windowStart);

        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(identifier);

        // Vérification si le compte est actuellement verrouillé
        if (userOpt.isPresent()) {
            UserEntity user = userOpt.get();
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                recordLoginAttempt(identifier, ipAddress, false, "ACCOUNT_LOCKED");
                securityOutboxService.publishSecurityEvent(
                        "LOGIN_FAILED",
                        user.getId(),
                        identifier,
                        ipAddress,
                        traceId,
                        Map.of("reason", "ACCOUNT_LOCKED", "lockedUntil", user.getLockedUntil().toString())
                );
                log.warn("Tentative de connexion sur un compte verrouillé [email={}, ip={}]", identifier, ipAddress);
                throw new AccountLockedException("Compte temporairement bloqué en raison de multiples échecs. Veuillez réessayer ultérieurement.");
            }
        }

        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            if (userOpt.isPresent()) {
                UserEntity user = userOpt.get();
                user.setLockedUntil(now.plus(LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES));
                userRepository.save(user);
                securityOutboxService.publishSecurityEvent(
                        "ACCOUNT_LOCKED",
                        user.getId(),
                        identifier,
                        ipAddress,
                        traceId,
                        Map.of("failedAttempts", failedAttempts)
                );
            }
            recordLoginAttempt(identifier, ipAddress, false, "ACCOUNT_LOCKED");
            log.warn("Seuil de 5 échecs atteint pour email={} [ip={}]. Compte bloqué 15 min.", identifier, ipAddress);
            throw new AccountLockedException("Compte temporairement bloqué suite à 5 tentatives de connexion infructueuses.");
        }

        // 2. Vérification du mot de passe avec BCrypt
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.getPassword(), userOpt.get().getPasswordHash())) {
            recordLoginAttempt(identifier, ipAddress, false, "INVALID_CREDENTIALS");

            if (userOpt.isPresent() && (failedAttempts + 1) >= MAX_FAILED_ATTEMPTS) {
                UserEntity user = userOpt.get();
                user.setLockedUntil(now.plus(LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES));
                userRepository.save(user);
                securityOutboxService.publishSecurityEvent(
                        "ACCOUNT_LOCKED",
                        user.getId(),
                        identifier,
                        ipAddress,
                        traceId,
                        Map.of("failedAttempts", failedAttempts + 1)
                );
            }

            securityOutboxService.publishSecurityEvent(
                    "LOGIN_FAILED",
                    userOpt.map(UserEntity::getId).orElse(null),
                    identifier,
                    ipAddress,
                    traceId,
                    Map.of("reason", "INVALID_CREDENTIALS")
            );

            log.warn("Échec d'authentification pour identifier={} [ip={}]", identifier, ipAddress);
            throw new AuthenticationFailedException("Identifiants invalides");
        }

        UserEntity user = userOpt.get();

        // 3. Connexion réussie : réinitialisation du verrou et journalisation
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        recordLoginAttempt(identifier, ipAddress, true, null);

        // 4. Génération des jetons (nouveau refresh token avec sa propre famille de rotation)
        UUID familyId = UUID.randomUUID();
        String rawRefreshToken = generateOpaqueToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant refreshExpiresAt = now.plus(refreshTokenValidityDays, ChronoUnit.DAYS);

        RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity(
                user.getId(),
                familyId,
                tokenHash,
                refreshExpiresAt,
                ipAddress,
                "Web Client"
        );
        refreshTokenRepository.save(refreshTokenEntity);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());

        // 5. Audit de succès
        securityOutboxService.publishSecurityEvent(
                "LOGIN_SUCCEEDED",
                user.getId(),
                user.getEmail(),
                ipAddress,
                traceId,
                Map.of("role", user.getRole())
        );

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getExpirationSeconds(),
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request, String ipAddress, String traceId) {
        String tokenHash = hashToken(request.getRefreshToken().trim());

        Optional<RefreshTokenEntity> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

        if (tokenOpt.isEmpty()) {
            log.warn("Jeton de rafraîchissement inconnu soumis");
            throw new InvalidTokenException("Jeton de rafraîchissement invalide");
        }

        RefreshTokenEntity currentToken = tokenOpt.get();

        // Détection de réutilisation d'un token déjà consommé/révoqué -> compromission suspectée !
        if (Boolean.TRUE.equals(currentToken.getIsRevoked())) {
            log.error("DÉTECTION DE RÉUTILISATION DE REFRESH TOKEN ! Révocation immédiate de la famille entière [familyId={}]", currentToken.getFamilyId());
            refreshTokenRepository.revokeFamily(currentToken.getFamilyId());
            securityOutboxService.publishSecurityEvent(
                    "TOKEN_REUSED",
                    currentToken.getUserId(),
                    null,
                    ipAddress,
                    traceId,
                    Map.of("familyId", currentToken.getFamilyId().toString())
            );
            throw new InvalidTokenException("Tentative de réutilisation d'un jeton révoqué. La session entière a été invalidée par mesure de sécurité.");
        }

        // Vérification d'expiration
        if (currentToken.getExpiresAt().isBefore(Instant.now())) {
            log.warn("Jeton de rafraîchissement expiré [id={}]", currentToken.getId());
            throw new TokenExpiredException("Le jeton de rafraîchissement a expiré");
        }

        // Rotation : révoquer le token actuel
        currentToken.setIsRevoked(true);
        refreshTokenRepository.save(currentToken);

        // Émettre un nouveau refresh token dans la même famille
        String newRawRefreshToken = generateOpaqueToken();
        String newTokenHash = hashToken(newRawRefreshToken);
        Instant newExpiresAt = Instant.now().plus(refreshTokenValidityDays, ChronoUnit.DAYS);

        RefreshTokenEntity newTokenEntity = new RefreshTokenEntity(
                currentToken.getUserId(),
                currentToken.getFamilyId(),
                newTokenHash,
                newExpiresAt,
                ipAddress,
                "Rotated Web Client"
        );
        refreshTokenRepository.save(newTokenEntity);

        // Générer le nouveau JWT access token
        UserEntity user = userRepository.findById(currentToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur associé au jeton introuvable"));

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());

        // Événement d'audit de rotation
        securityOutboxService.publishSecurityEvent(
                "TOKEN_ROTATED",
                user.getId(),
                user.getEmail(),
                ipAddress,
                traceId,
                Map.of("familyId", currentToken.getFamilyId().toString())
        );

        return new AuthResponse(
                newAccessToken,
                newRawRefreshToken,
                jwtTokenProvider.getExpirationSeconds(),
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken, String traceId) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String tokenHash = hashToken(rawRefreshToken.trim());
            Optional<RefreshTokenEntity> tokenOpt = refreshTokenRepository.findByTokenHash(tokenHash);

            if (tokenOpt.isPresent()) {
                RefreshTokenEntity token = tokenOpt.get();
                token.setIsRevoked(true);
                refreshTokenRepository.save(token);

                securityOutboxService.publishSecurityEvent(
                        "LOGOUT",
                        token.getUserId(),
                        null,
                        null,
                        traceId,
                        Map.of("familyId", token.getFamilyId().toString())
                );
                log.info("Jeton de rafraîchissement révoqué lors de la déconnexion [userId={}]", token.getUserId());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummaryDto getCurrentUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable"));

        return new UserSummaryDto(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getIsActive(),
                user.getCreatedAt()
        );
    }

    private void recordLoginAttempt(String email, String ipAddress, boolean wasSuccessful, String failureReason) {
        LoginAttemptEntity attempt = new LoginAttemptEntity(email, ipAddress, wasSuccessful, failureReason);
        loginAttemptRepository.save(attempt);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme SHA-256 non disponible", e);
        }
    }

    private String generateOpaqueToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
