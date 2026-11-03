package com.vigilia.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class CustomerResponse {

    private UUID id;
    private UUID userId;
    private String fullName;
    private LocalDate dateOfBirth;
    private String homeCity;
    private String homeCountry;
    private BigDecimal homeLat;
    private BigDecimal homeLon;
    private Integer cityPopulation;
    private String accountNumber;
    private Instant createdAt;
    private Instant updatedAt;

    public CustomerResponse() {
    }

    public CustomerResponse(UUID id, UUID userId, String fullName, LocalDate dateOfBirth,
                            String homeCity, String homeCountry, BigDecimal homeLat,
                            BigDecimal homeLon, Integer cityPopulation, String accountNumber,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.homeCity = homeCity;
        this.homeCountry = homeCountry;
        this.homeLat = homeLat;
        this.homeLon = homeLon;
        this.cityPopulation = cityPopulation;
        this.accountNumber = accountNumber;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getHomeCity() {
        return homeCity;
    }

    public void setHomeCity(String homeCity) {
        this.homeCity = homeCity;
    }

    public String getHomeCountry() {
        return homeCountry;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Backward compatibility helper getters for legacy contracts
    public Double getLatitude() {
        return homeLat != null ? homeLat.doubleValue() : null;
    }

    public Double getLongitude() {
        return homeLon != null ? homeLon.doubleValue() : null;
    }
}
