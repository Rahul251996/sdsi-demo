package com.digithon.assignment.scheduler;

import com.digithon.assignment.config.SchedulerConfig;
import com.digithon.assignment.service.ExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background worker that processes expired reservations in batches.
 *
 * Thread safety across multiple app instances: each expired reservation is
 * processed via ExpiryService.expireById(), which uses SELECT ... FOR UPDATE
 * on both the reservation and product rows. Only one worker succeeds; the
 * other sees the status already changed and returns without double-releasing.
 */
@Component
public class ExpiryCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupScheduler.class);

    private final ExpiryService expiryService;
    private final SchedulerConfig schedulerConfig;

    public ExpiryCleanupScheduler(ExpiryService expiryService,
                                   SchedulerConfig schedulerConfig) {
        this.expiryService = expiryService;
        this.schedulerConfig = schedulerConfig;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry-cleanup.fixed-delay-ms:30000}",
               initialDelayString = "${reservation.expiry-cleanup.initial-delay-ms:15000}")
    public void cleanupExpiredReservations() {
        if (!schedulerConfig.isExpiryCleanupEnabled()) {
            return;
        }

        try {
            int count = expiryService.expireBatch(schedulerConfig.getExpiryBatchSize());
            if (count > 0) {
                log.info("Scheduled cleanup expired {} reservations", count);
            }
        } catch (Exception e) {
            log.error("Scheduled cleanup failed: {}", e.getMessage(), e);
        }
    }
}