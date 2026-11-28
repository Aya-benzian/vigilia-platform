package com.vigilia.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class BeneficiaryCreateRequest {

    @NotBlank(message = "recipientAccountNumber is required")
    @Size(min = 6, max = 34, message = "recipientAccountNumber must be between 6 and 34 characters")
    @Pattern(regexp = "^[A-Z0-9-]+$", message = "recipientAccountNumber must contain only uppercase alphanumeric characters or hyphens")
    private String recipientAccountNumber;

    @Size(max = 100, message = "nickname cannot exceed 100 characters")
    private String nickname;

    public BeneficiaryCreateRequest() {
    }

    public BeneficiaryCreateRequest(String recipientAccountNumber, String nickname) {
        this.recipientAccountNumber = recipientAccountNumber;
        this.nickname = nickname;
    }

    public String getRecipientAccountNumber() {
        return recipientAccountNumber;
    }

    public void setRecipientAccountNumber(String recipientAccountNumber) {
        this.recipientAccountNumber = recipientAccountNumber;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
