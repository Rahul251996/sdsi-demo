package com.digithon.assignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "reservation")
public class ReservationProperties {

    private int defaultTtlSeconds = 120;
    private int maxTtlSeconds = 3600;
    private int maxQuantityPerReservation = 100;

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(int defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public int getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(int maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public int getMaxQuantityPerReservation() {
        return maxQuantityPerReservation;
    }

    public void setMaxQuantityPerReservation(int maxQuantityPerReservation) {
        this.maxQuantityPerReservation = maxQuantityPerReservation;
    }
}