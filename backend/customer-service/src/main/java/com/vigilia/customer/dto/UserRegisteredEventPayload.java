package com.vigilia.customer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRegisteredEventPayload {

    private UUID eventId;
    private String eventType;
    private UUID userId;
    private String email;
    private String fullName;
    private LocalDate dateOfBirth;
    private String homeCity;
    private String homeCountry;
    private BigDecimal homeLat;
    private BigDecimal homeLon;
    private Integer cityPopulation;
    private String traceId;

    public UserRegisteredEventPayload() {
    }

    public UserRegisteredEventPayload(UUID eventId, UUID userId, String email, String fullName) {
        this.eventId = eventId;
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
