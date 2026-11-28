package com.vigilia.notification.dto;

import java.time.Instant;
import java.util.Map;

public class ApiResponse<T> {
    private T data;
    private Map<String, Object> meta;

    public ApiResponse() {}

    public ApiResponse(T data, Map<String, Object> meta) {
        this.data = data;
        this.meta = meta;
    }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, Map.of("timestamp", Instant.now().toString()));
    }

    public static <T> ApiResponse<T> of(T data, Map<String, Object> meta) {
        return new ApiResponse<>(data, meta);
    }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }
}

