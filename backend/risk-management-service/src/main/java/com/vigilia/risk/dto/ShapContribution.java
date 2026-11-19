package com.vigilia.risk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a single SHAP feature attribution returned by M2 ML Serving.
 * Compatible with M2's /explain/purchase and /explain/transfer response contracts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShapContribution implements Serializable {

    @JsonProperty("feature")
    private String feature;

    @JsonProperty("value")
    private Object value;

    @JsonProperty("shap")
    private Double shap;

    public ShapContribution() {}

    public ShapContribution(String feature, Object value, Double shap) {
        this.feature = feature;
        this.value = value;
        this.shap = shap;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Double getShap() {
        return shap;
    }

    public void setShap(Double shap) {
        this.shap = shap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShapContribution that = (ShapContribution) o;
        return Objects.equals(feature, that.feature) &&
                Objects.equals(value, that.value) &&
                Objects.equals(shap, that.shap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(feature, value, shap);
    }

    @Override
    public String toString() {
        return "ShapContribution{" +
                "feature='" + feature + '\'' +
                ", value=" + value +
                ", shap=" + shap +
                '}';
    }
}

