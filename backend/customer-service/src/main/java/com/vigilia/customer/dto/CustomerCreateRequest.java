package com.vigilia.customer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class CustomerCreateRequest {

    private UUID userId;

    @Size(max = 200)
    private String fullName;

    // Legacy fields for backward compatibility
    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    private String email;

    @Past(message = "dateOfBirth must be in the past")
    private LocalDate dateOfBirth;

    private String gender;

    @Size(max = 100)
    private String homeCity;

    @Size(max = 2)
    private String homeCountry;

    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private BigDecimal homeLat;

    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private BigDecimal homeLon;

    @Min(value = 1, message = "cityPopulation must be at least 1")
    private Integer cityPopulation;

    private String accountNumber;

    public CustomerCreateRequest() {
    }

    public CustomerCreateRequest(UUID userId, String fullName, LocalDate dateOfBirth, String homeCity,
                                 String homeCountry, BigDecimal homeLat, BigDecimal homeLon,
                                 Integer cityPopulation, String accountNumber) {
        this.userId = userId;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.homeCity = homeCity;
        this.homeCountry = homeCountry;
        this.homeLat = homeLat;
        this.homeLon = homeLon;
        this.cityPopulation = cityPopulation;
        this.accountNumber = accountNumber;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getFullName() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        if (firstName != null || lastName != null) {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }
        return null;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getHomeCity() {
        return homeCity != null ? homeCity : "Paris";
    }

    public void setHomeCity(String homeCity) {
        this.homeCity = homeCity;
    }

    public String getHomeCountry() {
        return homeCountry != null ? homeCountry : "FR";
    }

    public void setHomeCountry(String homeCountry) {
        this.homeCountry = homeCountry;
    }

    public BigDecimal getHomeLat() {
        return homeLat;
    }

    public void setHomeLat(BigDecimal homeLat) {
        this.homeLat = homeLat;
    }

    public BigDecimal getHomeLon() {
        return homeLon;
    }

    public void setHomeLon(BigDecimal homeLon) {
        this.homeLon = homeLon;
    }

    // Helper for legacy Double latitude
    public Double getLatitude() {
        return homeLat != null ? homeLat.doubleValue() : null;
    }

    public void setLatitude(Double latitude) {
        this.homeLat = latitude != null ? BigDecimal.valueOf(latitude) : null;
    }

    // Helper for legacy Double longitude
    public Double getLongitude() {
        return homeLon != null ? homeLon.doubleValue() : null;
    }

    public void setLongitude(Double longitude) {
        this.homeLon = longitude != null ? BigDecimal.valueOf(longitude) : null;
    }

    public Integer getCityPopulation() {
        return cityPopulation;
    }

    public void setCityPopulation(Integer cityPopulation) {
        this.cityPopulation = cityPopulation;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
}
