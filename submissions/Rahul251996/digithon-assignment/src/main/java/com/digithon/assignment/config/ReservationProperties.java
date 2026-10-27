package com.digithon.assignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "reservation")
public class ReservationProperties {

    /** Default reservation TTL in seconds (120 = 2 minutes). */
    private int defaultTtlSeconds = 120;

    /** Maximum allowed TTL in seconds (3600 = 1 hour). Server-enforced. */
    private int maxTtlSeconds = 3600;

    /** Maximum quantity per single reservation request. */
    private int maxQuantityPerReservation = 100;

    // =========================================================================
    // Anti-hoarding settings
    // =========================================================================

    /**
     * Maximum number of active (RESERVED) reservations a single user may hold
     * across all SKUs. Set to 0 to disable this check.
     */
    private int maxActiveReservationsPerUser = 5;

    /**
     * Maximum total quantity a single user may have reserved across all SKUs.
     * This prevents a single user from reserving all available stock even if
     * they stay under the per-SKU purchase limit. Set to 0 to disable.
     */
    private int maxTotalReservedQuantityPerUser = 50;

    /**
     * Cooldown period in seconds after a user cancels a reservation before
     * they can reserve the same SKU again. This prevents reservation churn
     * (create→cancel→create loops that keep stock perpetually held).
     * Set to 0 to disable.
     */
    private int cooldownSecondsAfterCancel = 60;

    // =========================================================================
    // Getters and setters
    // =========================================================================

    public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
    public void setDefaultTtlSeconds(int v) { this.defaultTtlSeconds = v; }

    public int getMaxTtlSeconds() { return maxTtlSeconds; }
    public void setMaxTtlSeconds(int v) { this.maxTtlSeconds = v; }

    public int getMaxQuantityPerReservation() { return maxQuantityPerReservation; }
    public void setMaxQuantityPerReservation(int v) { this.maxQuantityPerReservation = v; }

    public int getMaxActiveReservationsPerUser() { return maxActiveReservationsPerUser; }
    public void setMaxActiveReservationsPerUser(int v) { this.maxActiveReservationsPerUser = v; }

    public int getMaxTotalReservedQuantityPerUser() { return maxTotalReservedQuantityPerUser; }
    public void setMaxTotalReservedQuantityPerUser(int v) { this.maxTotalReservedQuantityPerUser = v; }

    public int getCooldownSecondsAfterCancel() { return cooldownSecondsAfterCancel; }
    public void setCooldownSecondsAfterCancel(int v) { this.cooldownSecondsAfterCancel = v; }
}