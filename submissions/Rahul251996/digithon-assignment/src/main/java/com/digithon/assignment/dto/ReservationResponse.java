package com.digithon.assignment.dto;

import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationStatus;

import java.time.Instant;

public class ReservationResponse {

    private String reservationId;
    private String userId;
    private String sku;
    private int quantity;
    private ReservationStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public ReservationResponse() {
    }

    public static ReservationResponse from(Reservation r) {
        ReservationResponse resp = new ReservationResponse();
        resp.reservationId = r.getId();
        resp.userId = r.getUserId();
        resp.sku = r.getSku();
        resp.quantity = r.getQuantity();
        resp.status = r.getStatus();
        resp.expiresAt = r.getExpiresAt();
        resp.createdAt = r.getCreatedAt();
        resp.updatedAt = r.getUpdatedAt();
        return resp;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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