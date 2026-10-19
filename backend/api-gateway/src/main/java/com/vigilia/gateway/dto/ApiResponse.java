package com.vigilia.gateway.dto;

import java.util.Collections;
import java.util.Map;

public class ApiResponse<T> {

    private T data;
    private Map<String, Object> meta;

    public ApiResponse() {
        this.meta = Collections.emptyMap();
    }

    public ApiResponse(T data) {
        this.data = data;
        this.meta = Collections.emptyMap();
    }

    public ApiResponse(T data, Map<String, Object> meta) {
        this.data = data;
        this.meta = meta != null ? meta : Collections.emptyMap();
    }

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }

    public static <T> ApiResponse<T> of(T data, Map<String, Object> meta) {
        return new ApiResponse<>(data, meta);
    }

    public static <T> ApiResponse<T> empty() {
        return new ApiResponse<>(null, Collections.emptyMap());
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta != null ? meta : Collections.emptyMap();
    }
}
