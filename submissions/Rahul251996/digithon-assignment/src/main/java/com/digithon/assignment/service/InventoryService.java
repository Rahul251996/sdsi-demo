package com.digithon.assignment.service;

import com.digithon.assignment.config.ReservationProperties;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.Reservation;
import com.digithon.assignment.domain.ReservationEvent;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.dto.InventoryResponse;
import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.dto.ReservationResponse;
import com.digithon.assignment.exception.*;
import com.digithon.assignment.repository.ProductRepository;
import com.digithon.assignment.repository.ReservationEventRepository;
import com.digithon.assignment.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core service implementing all inventory reservation operations with
 * MySQL pessimistic locking (SELECT ... FOR UPDATE) to prevent overselling.
 *
 * Lock ordering strategy:
 * 1. Lock the product row (serializes operations per SKU)
 * 2. Lock reservation rows as needed
 * 3. Process expired reservations under the same lock
 * 4. Validate stock and purchase limits
 * 5. Update counters and reservation state
 * 6. Commit
 *
 * This approach serializes concurrent operations on the same SKU,
 * preventing both overselling and purchase-limit bypass.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationEventRepository eventRepository;
    private final ReservationProperties props;
    private final Clock clock;

    public InventoryService(ProductRepository productRepository,
                            ReservationRepository reservationRepository,
                            ReservationEventRepository eventRepository,
                            ReservationProperties props,
                            Clock clock) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.props = props;
        this.clock = clock;
    }

    // =========================================================================
    // M2: Reserve Stock
    // =========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(ReservationRequest request) {
        Instant now = clock.instant();

        validateRequest(request);

        int ttlSeconds = request.getTtlSeconds() != null
                ? request.getTtlSeconds() : props.getDefaultTtlSeconds();

        // Step 1: Lock the product row (serializes all operations on this SKU)
        Product product = productRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + request.getSku()));

        // Step 2: Expire any stale reservations for this product under lock
        expireProductReservationsUnderLock(product, now);

        // Step 3: Check stock availability
        if (product.getAvailableStock() < request.getQuantity()) {
            throw new InsufficientStockException(
                    "Requested quantity " + request.getQuantity()
                    + " exceeds available stock " + product.getAvailableStock());
        }

        // Step 4: Check purchase limit (confirmed + active reserved for user/SKU)
        // The product lock serializes this check, preventing concurrent bypass
        int confirmedQty = reservationRepository.sumConfirmedQuantity(
                request.getUserId(), request.getSku());
        int activeReservedQty = reservationRepository.sumActiveReservedQuantity(
                request.getUserId(), request.getSku(), now);
        int totalUserUsage = confirmedQty + activeReservedQty;

        if (totalUserUsage + request.getQuantity() > product.getPurchaseLimit()) {
            throw new PurchaseLimitExceededException(
                    "Purchase limit " + product.getPurchaseLimit()
                    + " would be exceeded (current: " + totalUserUsage
                    + ", requested: " + request.getQuantity() + ")");
        }

        // Step 5: Update product counters
        product.setAvailableStock(product.getAvailableStock() - request.getQuantity());
        product.setReservedStock(product.getReservedStock() + request.getQuantity());
        productRepository.save(product);

        // Step 6: Create reservation
        String reservationId = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        Reservation reservation = new Reservation(
                reservationId, request.getUserId(), request.getSku(),
                request.getQuantity(), ReservationStatus.RESERVED, expiresAt);
        reservationRepository.save(reservation);

        // Step 7: Record event
        eventRepository.save(new ReservationEvent(reservationId, "RESERVED"));

        log.info("Reservation created: id={}, user={}, sku={}, qty={}, expiresAt={}",
                reservationId, request.getUserId(), request.getSku(),
                request.getQuantity(), expiresAt);
        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M3: Confirm Reservation
    // =========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse confirm(String reservationId) {
        Instant now = clock.instant();

        // Step 1: Lock the reservation row
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Validate state — idempotent for CONFIRMED
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.debug("Reservation {} already confirmed", reservationId);
            return ReservationResponse.from(reservation);
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot confirm reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 3: Enforce expiry (now >= expiresAt means expired)
        if (!reservation.getExpiresAt().isAfter(now)) {
            // Transition to EXPIRED and reject
            expireReservationUnderProductLock(reservation);
            throw new InvalidStateTransitionException(
                    "Reservation " + reservationId + " has expired");
        }

        // Step 4: Lock the product row
        Product product = productRepository.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + reservation.getSku()));

        // Step 5: Expire any other stale reservations
        expireProductReservationsExcluding(product, now, reservationId);

        // Step 6: Transfer reserved -> sold
        product.setReservedStock(product.getReservedStock() - reservation.getQuantity());
        product.setSoldStock(product.getSoldStock() + reservation.getQuantity());
        productRepository.save(product);

        // Step 7: Update reservation status
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Step 8: Record event
        eventRepository.save(new ReservationEvent(reservationId, "CONFIRMED"));

        log.info("Reservation confirmed: id={}, qty={}", reservationId, reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M3: Cancel Reservation
    // =========================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(String reservationId) {
        Instant now = clock.instant();

        // Step 1: Lock the reservation row
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Validate state — idempotent for CANCELLED
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.debug("Reservation {} already cancelled", reservationId);
            return ReservationResponse.from(reservation);
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 3: If expired, transition to EXPIRED instead
        if (!reservation.getExpiresAt().isAfter(now)) {
            expireReservationUnderProductLock(reservation);
            return ReservationResponse.from(reservation);
        }

        // Step 4: Lock the product row
        Product product = productRepository.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + reservation.getSku()));

        // Step 5: Expire any other stale reservations
        expireProductReservationsExcluding(product, now, reservationId);

        // Step 6: Release reserved -> available (NOT touching sold — can't cancel a sale)
        product.setReservedStock(product.getReservedStock() - reservation.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + reservation.getQuantity());
        productRepository.save(product);

        // Step 7: Update reservation status
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // Step 8: Record event
        eventRepository.save(new ReservationEvent(reservationId, "CANCELLED"));

        log.info("Reservation cancelled: id={}, qty={}", reservationId, reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M3: Get Reservation (with lazy expiry)
    // =========================================================================

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Lazy expiry: if the reservation is RESERVED but expired, expire it
        if (reservation.getStatus() == ReservationStatus.RESERVED
                && !reservation.getExpiresAt().isAfter(clock.instant())) {
            expireReservationInNewTx(reservationId);
            reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reservation not found: " + reservationId));
        }

        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M5: Inventory Reporting
    // =========================================================================

    @Transactional(readOnly = true)
    public List<InventoryResponse> getAllInventory() {
        Instant now = clock.instant();
        List<Product> products = productRepository.findAll();

        return products.stream().map(p -> {
            expireForReport(p, now);
            Product refreshed = productRepository.findById(p.getSku()).orElse(p);
            return InventoryResponse.from(refreshed);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryBySku(String sku) {
        Instant now = clock.instant();
        Product product = productRepository.findById(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + sku));

        expireForReport(product, now);
        Product refreshed = productRepository.findById(sku).orElse(product);
        return InventoryResponse.from(refreshed);
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private void validateRequest(ReservationRequest request) {
        if (request.getTtlSeconds() != null) {
            if (request.getTtlSeconds() < 1
                    || request.getTtlSeconds() > props.getMaxTtlSeconds()) {
                throw new InvalidInputException(
                        "ttlSeconds must be between 1 and " + props.getMaxTtlSeconds());
            }
        }
        if (request.getQuantity() < 1
                || request.getQuantity() > props.getMaxQuantityPerReservation()) {
            throw new InvalidInputException(
                    "quantity must be between 1 and " + props.getMaxQuantityPerReservation());
        }
    }

    // =========================================================================
    // Expiry Helpers
    // =========================================================================

    /**
     * Expire all expired RESERVED reservations for a product while holding its lock.
     */
    private void expireProductReservationsUnderLock(Product product, Instant now) {
        List<Reservation> expired = reservationRepository.findExpiredReservations(now);
        for (Reservation r : expired) {
            if (r.getSku().equals(product.getSku())
                    && r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(product, r, "EXPIRED");
            }
        }
    }

    /**
     * Same as above but skips a specific reservation (being processed separately).
     */
    private void expireProductReservationsExcluding(Product product, Instant now,
                                                     String excludeId) {
        List<Reservation> expired = reservationRepository.findExpiredReservations(now);
        for (Reservation r : expired) {
            if (r.getSku().equals(product.getSku())
                    && r.getStatus() == ReservationStatus.RESERVED
                    && !r.getId().equals(excludeId)) {
                releaseStock(product, r, "EXPIRED");
            }
        }
    }

    /**
     * Expire a single reservation, acquiring the product lock first.
     */
    private void expireReservationUnderProductLock(Reservation r) {
        if (r.getStatus() != ReservationStatus.RESERVED) return;

        Product product = productRepository.findBySkuForUpdate(r.getSku()).orElse(null);
        if (product == null) return;

        releaseStock(product, r, "EXPIRED");
    }

    /**
     * Release reserved stock: reserved--, available++.
     * Updates both the product and reservation in one step.
     */
    private void releaseStock(Product product, Reservation r, String newStatus) {
        if (r.getStatus() != ReservationStatus.RESERVED) return;

        product.setReservedStock(product.getReservedStock() - r.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + r.getQuantity());
        r.setStatus(ReservationStatus.valueOf(newStatus));

        reservationRepository.save(r);
        productRepository.save(product);
        eventRepository.save(new ReservationEvent(r.getId(), newStatus));

        log.debug("Reservation {}: id={}, qty={}",
                newStatus.toLowerCase(), r.getId(), r.getQuantity());
    }

    // =========================================================================
    // New-transaction operations (for lazy expiry scenarios)
    // =========================================================================

    /**
     * Expire a reservation by ID in a fresh transaction.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void expireReservationInNewTx(String reservationId) {
        Instant now = clock.instant();
        Reservation r = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
        if (r != null && r.getStatus() == ReservationStatus.RESERVED
                && !r.getExpiresAt().isAfter(now)) {
            Product product = productRepository.findBySkuForUpdate(r.getSku()).orElse(null);
            if (product != null) {
                releaseStock(product, r, "EXPIRED");
            }
        }
    }

    /**
     * Expire stale reservations for a product in a fresh transaction (used by
     * read-only reporting to ensure accurate counters).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void expireForReport(Product product, Instant now) {
        Product locked = productRepository.findBySkuForUpdate(product.getSku()).orElse(null);
        if (locked == null) return;

        List<Reservation> expired = reservationRepository.findExpiredReservations(now);
        for (Reservation r : expired) {
            if (r.getSku().equals(locked.getSku())
                    && r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(locked, r, "EXPIRED");
            }
        }
    }
}