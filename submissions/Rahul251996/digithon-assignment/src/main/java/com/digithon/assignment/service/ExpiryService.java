package com.digithon.assignment.service;

import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationEvent;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.repository.ProductRepository;
import com.digithon.assignment.repository.ReservationEventRepository;
import com.digithon.assignment.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Separate service for expiry operations so that @Transactional works
 * across service boundaries (avoids Spring AOP self-invocation issue).
 *
 * This service handles:
 * - Releasing stock from expired reservations (stock: reserved -> available)
 * - Transitioning reservation status from RESERVED -> EXPIRED
 * - Recording expiry events
 *
 * All expiry operations use pessimistic locking (SELECT ... FOR UPDATE)
 * to ensure idempotency across concurrent workers.
 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final ReservationEventRepository eventRepository;
    private final Clock clock;

    // Self-injection to get the AOP proxy so that self-calls
    // (e.g. expireBatch -> expireById) go through the proxy and
    // each call gets its own @Transactional boundary.
    @Autowired
    @Lazy
    private ExpiryService self;

    public ExpiryService(ReservationRepository reservationRepository,
                         ProductRepository productRepository,
                         ReservationEventRepository eventRepository,
                         Clock clock) {
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    // =========================================================================
    // SKU-scoped expiry queries (not whole-table scans)
    // =========================================================================

    /**
     * Find expired RESERVED reservations for a specific SKU.
     * Uses the idx_reservations_status_expires_compound index.
     */
    public List<Reservation> findExpiredForSku(String sku, Instant now) {
        return reservationRepository.findExpiredForSku(sku, now);
    }

    /**
     * Find expired RESERVED reservations for a specific SKU, excluding one ID.
     */
    public List<Reservation> findExpiredForSkuExcluding(String sku, Instant now, String excludeId) {
        return reservationRepository.findExpiredForSkuExcluding(sku, now, excludeId);
    }

    /**
     * Find all expired reservations (across all SKUs) with a limit for batch cleanup.
     */
    public List<Reservation> findExpiredBatch(int limit, Instant now) {
        return reservationRepository.findExpiredWithLimit(
                now, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // =========================================================================
    // Expiry under existing product lock (for reserve/confirm/cancel flows)
    // =========================================================================

    /**
     * Expire all eligible reservations for a SKU while the product row is already locked.
     * The caller must be holding a pessimistic write lock on the Product row.
     *
     * @param product The locked product entity (already fetched with FOR UPDATE)
     * @param now Current instant from the injectable clock
     * @param excludeId Optional reservation ID to skip (the one being confirmed/cancelled)
     */
    public int expireUnderProductLock(Product product, Instant now, String excludeId) {
        List<Reservation> candidates = (excludeId != null)
                ? findExpiredForSkuExcluding(product.getSku(), now, excludeId)
                : findExpiredForSku(product.getSku(), now);

        int expired = 0;
        for (Reservation r : candidates) {
            if (r.getStatus() == ReservationStatus.RESERVED
                    && !r.getExpiresAt().isAfter(now)) {
                releaseStock(product, r);
                expired++;
            }
        }
        if (expired > 0) {
            log.debug("Expired {} reservations for sku={} under lock", expired, product.getSku());
        }
        return expired;
    }

    // =========================================================================
    // Expiry in a new transaction (for lazy expiry from read-only contexts)
    // =========================================================================

    /**
     * Expire a specific reservation by ID in a new write transaction.
     * Safe for use from read-only contexts (getReservation, getInventory).
     *
     * @return true if the reservation was expired, false otherwise
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean expireById(String reservationId) {
        Instant now = clock.instant();

        // Read without lock first to discover the SKU (avoids deadlock).
        // Then lock in the canonical order: Product first, Reservation second.
        // This matches the lock order in InventoryService and avoids deadlocks.
        Reservation preview = reservationRepository.findById(reservationId).orElse(null);
        if (preview == null) return false;

        // Step 1: Lock Product first (canonical lock order)
        Product product = productRepository.findBySkuForUpdate(preview.getSku()).orElse(null);
        if (product == null) {
            log.error("Product {} not found during expiry of {}", preview.getSku(), reservationId);
            return false;
        }

        // Step 2: Lock Reservation second
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r == null || r.getStatus() != ReservationStatus.RESERVED) return false;
        if (r.getExpiresAt().isAfter(now)) return false;

        // Step 3: Expire other stale reservations for this SKU (skipping this one)
        expireUnderProductLock(product, now, reservationId);

        // Step 4: Expire the target reservation
        releaseStock(product, r);
        return true;
    }

    /**
     * Expire all stale reservations for a SKU in a new write transaction.
     * Used by reporting to ensure accurate inventory counters.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int expireForSku(String sku) {
        Instant now = clock.instant();
        Product product = productRepository.findBySkuForUpdate(sku).orElse(null);
        if (product == null) return 0;
        return expireUnderProductLock(product, now, null);
    }

    // =========================================================================
    // Batch expiry (scheduler)
    // =========================================================================

    /**
     * Expire a batch of expired reservations. Used by the scheduled cleanup worker.
     * Each reservation is processed in its own transaction (via self.proxy proxy)
     * so that product locks are released between items.
     *
     * @return number of reservations expired
     */
    // readOnly outer tx for the find query; each self.expireById() is REQUIRES_NEW.
    @Transactional(readOnly = true)
    public int expireBatch(int limit) {
        Instant now = clock.instant();
        List<Reservation> expired = findExpiredBatch(limit, now);
        int count = 0;

        for (Reservation r : expired) {
            if (self.expireById(r.getId())) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Core stock-release method (called only under product lock)
    // =========================================================================

    /**
     * Release reserved stock for a single reservation.
     * MUST be called while holding a pessimistic write lock on the product row.
     * Guard clause prevents double-release.
     *
     * Side effects:
     * - product.reservedStock -= reservation.quantity
     * - product.availableStock += reservation.quantity
     * - reservation.status = EXPIRED
     * - ReservationEvent("EXPIRED") recorded
     */
    private void releaseStock(Product product, Reservation r) {
        if (r.getStatus() != ReservationStatus.RESERVED) {
            return; // Idempotency guard: already processed
        }

        product.setReservedStock(product.getReservedStock() - r.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + r.getQuantity());
        r.setStatus(ReservationStatus.EXPIRED);

        reservationRepository.save(r);
        productRepository.save(product);
        eventRepository.save(new ReservationEvent(r.getId(), "EXPIRED"));

        log.debug("Expired reservation: id={}, sku={}, qty={}",
                r.getId(), r.getSku(), r.getQuantity());
    }
}