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
 * Core inventory reservation service.
 *
 * Concurrency strategy: MySQL pessimistic row-level locking.
 *
 * Lock ordering (always the same to avoid deadlocks):
 *   1. Product row (SELECT ... FOR UPDATE) — serializes by SKU
 *   2. Reservation row (SELECT ... FOR UPDATE) — when needed
 *
 * The product lock serializes ALL operations on a given SKU. This correctly
 * prevents both overselling (stock counter protection) and purchase-limit
 * bypass (two threads for the same user+SKU are serialized; thread B sees
 * thread A's committed reservation before its own limit check).
 *
 * Self-invocation note: Expiry work is delegated to {@link ExpiryService}
 * (a separate Spring bean) so that @Transactional annotations on those
 * methods are honoured through the AOP proxy.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationEventRepository eventRepository;
    private final ExpiryService expiryService;
    private final ReservationProperties props;
    private final Clock clock;

    public InventoryService(ProductRepository productRepository,
                            ReservationRepository reservationRepository,
                            ReservationEventRepository eventRepository,
                            ExpiryService expiryService,
                            ReservationProperties props,
                            Clock clock) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.expiryService = expiryService;
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

        // Step 1: Lock the product row (serializes all ops on this SKU)
        Product product = productRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found: " + request.getSku()));

        // Step 2: Expire stale reservations under the product lock (via ExpiryService method)
        // This is an internal delegate call within the same transaction — no proxy issue.
        expireStaleForProduct(product, now);

        // Step 3: Check stock
        if (product.getAvailableStock() < request.getQuantity()) {
            throw new InsufficientStockException(
                    "Requested quantity " + request.getQuantity()
                    + " exceeds available stock " + product.getAvailableStock());
        }

        // Step 4: Purchase limit check
        // Because we hold the product lock, no other thread can create/confirm/cancel
        // a reservation for this SKU until we commit. So the sum queries see a
        // consistent snapshot of committed state.
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

        // Step 1: Lock the reservation
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Idempotency — already confirmed
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.debug("Reservation {} already confirmed", reservationId);
            return ReservationResponse.from(reservation);
        }

        // Step 3: Validate state
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot confirm reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 4: Expiry check — if expired, expire it and reject
        if (!reservation.getExpiresAt().isAfter(now)) {
            expireReservationUnderLock(reservation, now);
            throw new InvalidStateTransitionException(
                    "Reservation " + reservationId + " has expired");
        }

        // Step 5: Lock the product row
        Product product = productRepository.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + reservation.getSku()));

        // Step 6: Expire any other stale reservations for this SKU
        expireStaleForProductExcluding(product, now, reservationId);

        // Step 7: Transfer reserved -> sold
        product.setReservedStock(product.getReservedStock() - reservation.getQuantity());
        product.setSoldStock(product.getSoldStock() + reservation.getQuantity());
        productRepository.save(product);

        // Step 8: Update status
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        // Step 9: Record event
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

        // Step 1: Lock the reservation
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Idempotency — already cancelled
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.debug("Reservation {} already cancelled", reservationId);
            return ReservationResponse.from(reservation);
        }

        // Step 3: Validate state
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 4: If expired, expire it instead (stock release happens once)
        if (!reservation.getExpiresAt().isAfter(now)) {
            expireReservationUnderLock(reservation, now);
            return ReservationResponse.from(reservation);
        }

        // Step 5: Lock the product row
        Product product = productRepository.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + reservation.getSku()));

        // Step 6: Expire stale reservations for this SKU
        expireStaleForProductExcluding(product, now, reservationId);

        // Step 7: Release reserved -> available
        product.setReservedStock(product.getReservedStock() - reservation.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + reservation.getQuantity());
        productRepository.save(product);

        // Step 8: Update status
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // Step 9: Record event
        eventRepository.save(new ReservationEvent(reservationId, "CANCELLED"));

        log.info("Reservation cancelled: id={}, qty={}", reservationId, reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M3: Get Reservation (with lazy expiry via ExpiryService proxy)
    // =========================================================================

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Lazy expiry: if expired, delegate to ExpiryService (separate bean, so
        // @Transactional on expireById() works through the AOP proxy)
        if (reservation.getStatus() == ReservationStatus.RESERVED
                && !reservation.getExpiresAt().isAfter(clock.instant())) {
            expiryService.expireById(reservationId);
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
        List<Product> products = productRepository.findAll();

        return products.stream().map(p -> {
            // Lazy-expire via ExpiryService (proxy-aware)
            expiryService.expireForSku(p.getSku());
            Product refreshed = productRepository.findById(p.getSku()).orElse(p);
            return InventoryResponse.from(refreshed);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryBySku(String sku) {
        Product product = productRepository.findById(sku)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found: " + sku));

        // Lazy-expire via ExpiryService (proxy-aware)
        expiryService.expireForSku(sku);
        Product refreshed = productRepository.findById(sku).orElse(product);
        return InventoryResponse.from(refreshed);
    }

    // =========================================================================
    // Private helpers (internal, called within active transactions)
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

    /**
     * Expire stale reservations for a product. Called while holding the product lock.
     * Uses the package-private findExpiredForSku query, not findExpiredReservations
     * (which would load all-SKU expired reservations into memory).
     */
    private void expireStaleForProduct(Product product, Instant now) {
        List<Reservation> candidates = expiryService.findExpiredForSku(product.getSku(), now);
        for (Reservation r : candidates) {
            if (r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(product, r);
            }
        }
    }

    private void expireStaleForProductExcluding(Product product, Instant now, String excludeId) {
        List<Reservation> candidates = expiryService.findExpiredForSkuExcluding(
                product.getSku(), now, excludeId);
        for (Reservation r : candidates) {
            if (r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(product, r);
            }
        }
    }

    /**
     * Expire a single reservation, acquiring the product lock within the
     * current transaction (valid since the product may already be locked).
     */
    private void expireReservationUnderLock(Reservation r, Instant now) {
        if (r.getStatus() != ReservationStatus.RESERVED) return;
        if (r.getExpiresAt().isAfter(now)) return;

        Product product = productRepository.findBySkuForUpdate(r.getSku()).orElse(null);
        if (product == null) return;

        releaseStock(product, r);
    }

    /**
     * Release stock: reserved--, available++.
     * Must be called within an active transaction holding the product lock.
     * Guard clause prevents double-release.
     */
    private void releaseStock(Product product, Reservation r) {
        if (r.getStatus() != ReservationStatus.RESERVED) return;

        product.setReservedStock(product.getReservedStock() - r.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + r.getQuantity());
        r.setStatus(ReservationStatus.EXPIRED);

        reservationRepository.save(r);
        productRepository.save(product);
        eventRepository.save(new ReservationEvent(r.getId(), "EXPIRED"));

        log.debug("Reservation expired (inline): id={}, qty={}", r.getId(), r.getQuantity());
    }
}