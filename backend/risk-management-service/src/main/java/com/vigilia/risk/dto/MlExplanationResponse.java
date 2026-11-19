package com.vigilia.risk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the M2 ML Serving explanation response contract (Design 2 / Final v4.2).
 * Returned by POST /explain/purchase and POST /explain/transfer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlExplanationResponse implements Serializable {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("model_version")
    private String modelVersion;

    @JsonProperty("base_value")
    private Double baseValue;

    @JsonProperty("contributions")
    private List<ShapContribution> contributions = new ArrayList<>();

    public MlExplanationResponse() {}

    public MlExplanationResponse(String requestId,
                                 String modelName,
                                 String modelVersion,
                                 Double baseValue,
                                 List<ShapContribution> contributions) {
        this.requestId = requestId;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.baseValue = baseValue;
        this.contributions = contributions != null ? contributions : new ArrayList<>();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public Double getBaseValue() {
        return baseValue;
    }

    public void setBaseValue(Double baseValue) {
        this.baseValue = baseValue;
    }

    public List<ShapContribution> getContributions() {
        return contributions;
    }

    public void setContributions(List<ShapContribution> contributions) {
        this.contributions = contributions != null ? contributions : new ArrayList<>();
    }
}

