package com.vigilia.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Demande d'inscription utilisateur")
public class RegisterRequest {

    @Schema(description = "Adresse email de l'utilisateur", example = "sara@example.com")
    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "L'email doit être valide")
    private String email;

    @Schema(description = "Mot de passe sécurisé (minimum 8 caractères)", example = "S3cure-Passphrase!")
    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, max = 100, message = "Le mot de passe doit comporter au moins 8 caractères")
    private String password;

    @Schema(description = "Nom d'utilisateur optionnel", example = "sara_benali")
    private String username;

    @Schema(description = "Rôle demandé (toute tentative d'auto-attribution ADMIN/ANALYST est refusée)", example = "CUSTOMER")
    private String role;

    @Schema(description = "Nom complet du client", example = "Sara Benali")
    @Size(max = 200)
    private String fullName;

    @Schema(description = "Date de naissance (YYYY-MM-DD)", example = "1996-04-18")
    private String dateOfBirth;

    @Schema(description = "Code pays ISO-2", example = "FR")
    @Size(min = 2, max = 2)
    private String homeCountry;

    @Schema(description = "Ville de résidence", example = "Lyon")
    @Size(max = 100)
    private String homeCity;

    public RegisterRequest() {
    }

    public RegisterRequest(String email, String password) {
        this.email = email;
        this.password = password;
        this.username = email;
    }

    public RegisterRequest(String username, String email, String password) {
        this.username = username;
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return (username != null && !username.isBlank()) ? username : email;
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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getHomeCountry() {
        return homeCountry;
    }

    public void setHomeCountry(String homeCountry) {
        this.homeCountry = homeCountry;
    }

    public String getHomeCity() {
        return homeCity;
    }

    public void setHomeCity(String homeCity) {
        this.homeCity = homeCity;
    }
}
