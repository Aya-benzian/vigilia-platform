package com.vigilia.customer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CustomerLookupResponse {

    @JsonProperty("account_number")
    private String accountNumber;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("exists")
    private boolean exists;

    public CustomerLookupResponse() {
    }

    public CustomerLookupResponse(String accountNumber, String displayName, boolean exists) {
        this.accountNumber = accountNumber;
        this.displayName = displayName;
        this.exists = exists;
    }

    public static CustomerLookupResponse notFound(String accountNumber) {
        return new CustomerLookupResponse(accountNumber, null, false);
    }

    public static CustomerLookupResponse found(String accountNumber, String displayName) {
        return new CustomerLookupResponse(accountNumber, displayName, true);
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isExists() {
        return exists;
    }

    public void setExists(boolean exists) {
        this.exists = exists;
    }
}
