package com.vigilia.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Informations sur l'utilisateur connecté")
public class UserSummaryDto {

    private UUID userId;
    private String email;
    private String role;
    private Boolean isActive;
    private Instant createdAt;

    public UserSummaryDto() {
    }

    public UserSummaryDto(UUID userId, String email, String role, Boolean isActive, Instant createdAt) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.isActive = isActive;
        this.createdAt = createdAt;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
