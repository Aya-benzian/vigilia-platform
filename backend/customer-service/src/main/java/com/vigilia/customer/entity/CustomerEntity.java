package com.vigilia.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entity representing a Customer profile.
 * Schema: customer | Table: customers
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", unique = true, nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "home_city", nullable = false, length = 100)
    private String homeCity;

    @Column(name = "home_country", nullable = false, length = 2)
    private String homeCountry;

    @Column(name = "home_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal homeLat;

    @Column(name = "home_lon", nullable = false, precision = 9, scale = 6)
    private BigDecimal homeLon;

    @Column(name = "city_population", nullable = false)
    private Integer cityPopulation;

    @Column(name = "account_number", unique = true, nullable = false, length = 34)
    private String accountNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CustomerEntity() {
    }

    public CustomerEntity(UUID userId, String fullName, LocalDate dateOfBirth, String homeCity,
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
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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
}
