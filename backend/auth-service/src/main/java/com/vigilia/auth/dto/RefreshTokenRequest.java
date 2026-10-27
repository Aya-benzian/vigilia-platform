package com.vigilia.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Demande de rafraîchissement du jeton d'accès")
public class RefreshTokenRequest {

    @Schema(description = "Jeton de rafraîchissement opaque", example = "dGhpcy1pcy1hLXJlZnJlc2gtdG9rZW4...")
    @NotBlank(message = "Le refresh token est obligatoire")
    private String refreshToken;

    public RefreshTokenRequest() {
    }

    public RefreshTokenRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
