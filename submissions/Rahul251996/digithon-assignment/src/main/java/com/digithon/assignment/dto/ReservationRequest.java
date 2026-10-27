package com.digithon.assignment.dto;

import jakarta.validation.constraints.*;

public class ReservationRequest {

    @NotBlank(message = "userId is required")
    @Size(max = 255, message = "userId must not exceed 255 characters")
    private String userId;

    @NotBlank(message = "sku is required")
    @Size(max = 100, message = "sku must not exceed 100 characters")
    private String sku;

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    @Max(value = 100, message = "quantity exceeds maximum allowed")
    private Integer quantity;

    @Min(value = 1, message = "ttlSeconds must be at least 1")
    private Integer ttlSeconds;

    public ReservationRequest() {
    }

    public ReservationRequest(String userId, String sku, Integer quantity, Integer ttlSeconds) {
        this.userId = userId;
        this.sku = sku;
        this.quantity = quantity;
        this.ttlSeconds = ttlSeconds;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(Integer ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}