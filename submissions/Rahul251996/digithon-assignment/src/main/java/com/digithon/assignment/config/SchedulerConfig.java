package com.digithon.assignment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Value("${reservation.expiry-cleanup.batch-size:100}")
    private int expiryBatchSize;

    @Value("${reservation.expiry-cleanup.enabled:true}")
    private boolean expiryCleanupEnabled;

    @Value("${reservation.expiry-cleanup.fixed-delay-ms:30000}")
    private long fixedDelayMs;

    @Value("${reservation.expiry-cleanup.initial-delay-ms:15000}")
    private long initialDelayMs;

    public int getExpiryBatchSize() {
        return expiryBatchSize;
    }

    public boolean isExpiryCleanupEnabled() {
        return expiryCleanupEnabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }
}