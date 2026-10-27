package com.digithon.assignment.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservation_events")
public class ReservationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", length = 36, nullable = false)
    private String reservationId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ReservationEvent() {
    }

    public ReservationEvent(String reservationId, String eventType) {
        this.reservationId = reservationId;
        this.eventType = eventType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReservationId() { return reservationId; }
    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}