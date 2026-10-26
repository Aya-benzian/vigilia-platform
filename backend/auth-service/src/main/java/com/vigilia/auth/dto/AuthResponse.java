package com.vigilia.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Réponse d'authentification contenant les jetons d'accès et de rafraîchissement")
public class AuthResponse {

    @Schema(description = "Jeton d'accès JWT RS256", example = "eyJhbGciOiJSUzI1NiIs...")
    private String accessToken;

    @Schema(description = "Jeton de rafraîchissement opaque", example = "dGhpcy1pcy1hLXJlZnJlc2gtdG9rZW4...")
    private String refreshToken;

    @Schema(description = "Type de jeton", example = "Bearer")
    private String tokenType = "Bearer";

    @Schema(description = "Durée de validité en secondes", example = "900")
    private long expiresIn = 900;

    @Schema(description = "Identifiant unique de l'utilisateur", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID userId;

    @Schema(description = "Adresse email de l'utilisateur", example = "sara@example.com")
    private String email;

    @Schema(description = "Nom d'utilisateur", example = "sara@example.com")
    private String username;

    @Schema(description = "Rôle unique", example = "CUSTOMER")
    private String role;

    @Schema(description = "Liste des rôles", example = "[\"ROLE_CUSTOMER\"]")
    private List<String> roles;

    public AuthResponse() {
    }

    public AuthResponse(String accessToken, String refreshToken, long expiresIn, UUID userId, String email, String role) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.email = email;
        this.username = email;
        this.role = role;
        this.roles = List.of("ROLE_" + role);
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username != null ? username : email;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }
}
