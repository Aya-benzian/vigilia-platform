package com.vigilia.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Demande d'authentification utilisateur")
public class LoginRequest {

    @Schema(description = "Adresse email ou nom d'utilisateur", example = "sara@example.com")
    private String email;

    @Schema(description = "Nom d'utilisateur (alias d'email)", example = "sara@example.com")
    private String username;

    @Schema(description = "Mot de passe", example = "S3cure-Passphrase!")
    @NotBlank(message = "Le mot de passe est obligatoire")
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String email, String password) {
        this.email = email;
        this.username = email;
        this.password = password;
    }

    public String getEmail() {
        if (email != null && !email.isBlank()) {
            return email;
        }
        return username;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        if (username != null && !username.isBlank()) {
            return username;
        }
        return email;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String resolveIdentifier() {
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return "";
    }
}
