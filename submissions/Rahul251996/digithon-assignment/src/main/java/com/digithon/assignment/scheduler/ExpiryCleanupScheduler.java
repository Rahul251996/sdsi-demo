package com.digithon.assignment.scheduler;

import com.digithon.assignment.config.SchedulerConfig;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationEvent;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.repository.ProductRepository;
import com.digithon.assignment.repository.ReservationEventRepository;
import com.digithon.assignment.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled worker that processes expired reservations in batches.
 * Safe across multiple application instances due to MySQL row-level locking.
 */
@Component
public class ExpiryCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupScheduler.class);

    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final ReservationEventRepository eventRepository;
    private final Clock clock;
    private final SchedulerConfig schedulerConfig;

    public ExpiryCleanupScheduler(ReservationRepository reservationRepository,
                                   ProductRepository productRepository,
                                   ReservationEventRepository eventRepository,
                                   Clock clock,
                                   SchedulerConfig schedulerConfig) {
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
        this.schedulerConfig = schedulerConfig;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry-cleanup.fixed-delay-ms:30000}",
               initialDelayString = "${reservation.expiry-cleanup.initial-delay-ms:15000}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cleanupExpiredReservations() {
        if (!schedulerConfig.isExpiryCleanupEnabled()) {
            return;
        }

        Instant now = clock.instant();
        int batchSize = schedulerConfig.getExpiryBatchSize();
        int totalExpired = 0;

        try {
            List<Reservation> expired = reservationRepository.findExpiredReservationsWithLimit(
                    now, PageRequest.of(0, batchSize));

            for (Reservation r : expired) {
                try {
                    expireSingleReservation(r);
                    totalExpired++;
                } catch (Exception e) {
                    log.warn("Failed to expire reservation {}: {}", r.getId(), e.getMessage());
                }
            }

            if (totalExpired > 0) {
                log.info("Cleanup processed {} expired reservations", totalExpired);
            }
        } catch (Exception e) {
            log.error("Expiry cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Expire a single reservation atomically using row-level locking.
     */
    private void expireSingleReservation(Reservation r) {
        // Re-fetch with lock to ensure we don't expire an already-processed reservation
        Reservation locked = reservationRepository.findByIdForUpdate(r.getId()).orElse(null);
        if (locked == null || locked.getStatus() != ReservationStatus.RESERVED) {
            return;
        }

        Instant now = clock.instant();
        if (locked.getExpiresAt().isAfter(now)) {
            return; // Not yet expired — another instance may have already handled it
        }

        Product product = productRepository.findBySkuForUpdate(locked.getSku()).orElse(null);
        if (product == null) {
            log.error("Product {} not found for reservation {}", locked.getSku(), locked.getId());
            return;
        }

        product.setReservedStock(product.getReservedStock() - locked.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + locked.getQuantity());
        locked.setStatus(ReservationStatus.EXPIRED);

        reservationRepository.save(locked);
        productRepository.save(product);
        eventRepository.save(new ReservationEvent(locked.getId(), "EXPIRED"));

        log.debug("Cleanup expired reservation: id={}, sku={}, qty={}",
                locked.getId(), locked.getSku(), locked.getQuantity());
    }
}