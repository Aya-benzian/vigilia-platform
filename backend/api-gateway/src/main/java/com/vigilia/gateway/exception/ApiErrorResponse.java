package com.vigilia.gateway.exception;

public class ApiErrorResponse {

    private String code;
    private String message;
    private int status;
    private String correlationId;

    public ApiErrorResponse() {
    }

    public ApiErrorResponse(String code, String message, int status, String correlationId) {
        this.code = code;
        this.message = message;
        this.status = status;
        this.correlationId = correlationId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
