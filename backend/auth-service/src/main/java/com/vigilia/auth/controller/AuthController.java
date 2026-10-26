package com.vigilia.auth.controller;

import com.vigilia.auth.dto.ApiResponse;
import com.vigilia.auth.dto.AuthResponse;
import com.vigilia.auth.dto.LoginRequest;
import com.vigilia.auth.dto.RefreshTokenRequest;
import com.vigilia.auth.dto.RegisterRequest;
import com.vigilia.auth.dto.UserSummaryDto;
import com.vigilia.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Endpoints d'inscription, connexion, rafraîchissement et profil")
public class AuthController {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Inscription d'un nouvel utilisateur", description = "Crée un compte utilisateur avec le rôle strict CUSTOMER")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest) {

        String ipAddress = resolveClientIp(servletRequest);
        String traceId = resolveCorrelationId(servletRequest);

        AuthResponse authResponse = authService.register(request, ipAddress, traceId);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(authResponse, "Utilisateur enregistré avec succès"));
    }

    @PostMapping("/login")
    @Operation(summary = "Authentification utilisateur", description = "Valide les identifiants et délivre les jetons JWT RS256 et refresh token")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {

        String ipAddress = resolveClientIp(servletRequest);
        String traceId = resolveCorrelationId(servletRequest);

        AuthResponse authResponse = authService.login(request, ipAddress, traceId);

        return ResponseEntity.ok(ApiResponse.success(authResponse, "Authentification réussie"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rafraîchissement du jeton d'accès", description = "Renouvelle le jeton d'accès avec rotation du refresh token et détection de réutilisation")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest servletRequest) {

        String ipAddress = resolveClientIp(servletRequest);
        String traceId = resolveCorrelationId(servletRequest);

        AuthResponse authResponse = authService.refreshToken(request, ipAddress, traceId);

        return ResponseEntity.ok(ApiResponse.success(authResponse, "Jeton rafraîchi avec succès"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Déconnexion utilisateur", description = "Révoque le jeton de rafraîchissement")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest servletRequest) {

        String traceId = resolveCorrelationId(servletRequest);
        String rawRefreshToken = request != null ? request.getRefreshToken() : null;

        authService.logout(rawRefreshToken, traceId);

        return ResponseEntity.ok(ApiResponse.success(null, "Déconnexion réussie"));
    }

    @GetMapping("/me")
    @Operation(summary = "Profil de l'utilisateur connecté", description = "Retourne les informations de l'utilisateur authentifié")
    public ResponseEntity<ApiResponse<UserSummaryDto>> getMe(
            @AuthenticationPrincipal String userIdString) {

        UUID userId = UUID.fromString(userIdString);
        UserSummaryDto userSummary = authService.getCurrentUser(userId);

        return ResponseEntity.ok(ApiResponse.success(userSummary, "Profil récupéré avec succès"));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isBlank()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        if (request != null) {
            correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                return correlationId;
            }
        }
        return UUID.randomUUID().toString();
    }
}
