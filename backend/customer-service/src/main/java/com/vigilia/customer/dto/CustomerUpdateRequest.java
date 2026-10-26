package com.vigilia.customer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class CustomerUpdateRequest {

    @Size(min = 2, max = 200, message = "fullName must be between 2 and 200 characters")
    private String fullName;

    @Size(min = 1, max = 100, message = "homeCity must be between 1 and 100 characters")
    private String homeCity;

    @Pattern(regexp = "^[A-Z]{2}$", message = "homeCountry must be a 2-letter ISO country code (e.g. FR)")
    private String homeCountry;

    @DecimalMin(value = "-90.0", message = "homeLat must be >= -90.0")
    @DecimalMax(value = "90.0", message = "homeLat must be <= 90.0")
    private BigDecimal homeLat;

    @DecimalMin(value = "-180.0", message = "homeLon must be >= -180.0")
    @DecimalMax(value = "180.0", message = "homeLon must be <= 180.0")
    private BigDecimal homeLon;

    @Min(value = 1, message = "cityPopulation must be at least 1")
    private Integer cityPopulation;

    public CustomerUpdateRequest() {
    }

    public CustomerUpdateRequest(String fullName, String homeCity, String homeCountry,
                                 BigDecimal homeLat, BigDecimal homeLon, Integer cityPopulation) {
        this.fullName = fullName;
        this.homeCity = homeCity;
        this.homeCountry = homeCountry;
        this.homeLat = homeLat;
        this.homeLon = homeLon;
        this.cityPopulation = cityPopulation;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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
}
