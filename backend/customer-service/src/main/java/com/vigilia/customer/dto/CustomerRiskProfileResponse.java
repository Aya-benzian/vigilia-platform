package com.vigilia.customer.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Risk profile response providing demographic and behavioral baseline data
 * for Risk Management and fraud evaluation models.
 */
public class CustomerRiskProfileResponse {

    private UUID customerId;
    private UUID userId;
    private String accountNumber;
    private int age;
    private String gender;
    private String homeCity;
    private String homeCountry;
    private Double latitude;
    private Double longitude;
    private Integer cityPopulation;
    private Instant createdAt;

    public CustomerRiskProfileResponse() {
    }

    public CustomerRiskProfileResponse(UUID customerId, int age, String gender, Double latitude, Double longitude, Integer cityPopulation) {
        this.customerId = customerId;
        this.age = age;
        this.gender = gender;
        this.latitude = latitude;
        this.longitude = longitude;
        this.cityPopulation = cityPopulation;
    }

    public CustomerRiskProfileResponse(UUID customerId, UUID userId, String accountNumber, int age,
                                       String gender, String homeCity, String homeCountry,
                                       Double latitude, Double longitude, Integer cityPopulation,
                                       Instant createdAt) {
        this.customerId = customerId;
        this.userId = userId;
        this.accountNumber = accountNumber;
        this.age = age;
        this.gender = gender;
        this.homeCity = homeCity;
        this.homeCountry = homeCountry;
        this.latitude = latitude;
        this.longitude = longitude;
        this.cityPopulation = cityPopulation;
        this.createdAt = createdAt;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
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

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Integer getCityPopulation() {
        return cityPopulation;
    }

    public void setCityPopulation(Integer cityPopulation) {
        this.cityPopulation = cityPopulation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
