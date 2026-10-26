package com.vigilia.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CustomerPreferenceUpdateRequest {

    private Boolean emailNotifications;

    private Boolean inAppNotifications;

    @Size(min = 2, max = 10, message = "language must be between 2 and 10 characters")
    @Pattern(regexp = "^[a-zA-Z_-]{2,10}$", message = "language code must be valid (e.g. en, fr, es)")
    private String language;

    public CustomerPreferenceUpdateRequest() {
    }

    public CustomerPreferenceUpdateRequest(Boolean emailNotifications, Boolean inAppNotifications, String language) {
        this.emailNotifications = emailNotifications;
        this.inAppNotifications = inAppNotifications;
        this.language = language;
    }

    public Boolean getEmailNotifications() {
        return emailNotifications;
    }

    public void setEmailNotifications(Boolean emailNotifications) {
        this.emailNotifications = emailNotifications;
    }

    public Boolean getInAppNotifications() {
        return inAppNotifications;
    }

    public void setInAppNotifications(Boolean inAppNotifications) {
        this.inAppNotifications = inAppNotifications;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
