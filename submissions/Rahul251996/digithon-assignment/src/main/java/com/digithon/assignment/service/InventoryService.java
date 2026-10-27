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
import org.springframework.data.domain.PageRequest;
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
 * CONCURRENCY STRATEGY: MySQL pessimistic row-level locking.
 *
 * CANONICAL LOCK ORDER (STRICT — prevents deadlocks):
 *   ALWAYS lock Product first, Reservation second.
 *   NEVER lock Reservation first then Product — that reverses the order.
 *
 * This means confirm/cancel MUST read a reservation preview (no lock)
 * to discover the SKU, then lock Product, then re-lock Reservation.
 * The preview read is safe: even if another thread changes the reservation
 * between preview and lock, we validate under the Reservation lock.
 *
 * The Product lock serializes ALL operations on a given SKU, preventing:
 *   1. Overselling: concurrent reads of availableStock can't both deduct.
 *   2. Purchase-limit bypass: Thread B sees Thread A's committed reservation.
 *
 * ANTI-HOARDING POLICY:
 *   The system reserves stock immediately (not on "add-to-cart").
 *   To prevent indefinite blocking, three configurable guards are enforced:
 *   A. maxActiveReservationsPerUser — per-user cap on active reservations across SKUs
 *   B. maxTotalReservedQuantityPerUser — per-user cap on total reserved quantity
 *   C. cooldownSecondsAfterCancel — delay before re-reserving the same SKU after cancel
 *   All are configurable; set to 0 to disable each check.
 *   All checks run under the SKU-level Product lock, serialized per SKU.
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
    // Lock: Product only (no reservation to lock yet).
    // This serializes all operations on this SKU. Safe.

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(ReservationRequest request) {
        Instant now = clock.instant();
        validateRequest(request);
        int ttlSeconds = request.getTtlSeconds() != null
                ? request.getTtlSeconds() : props.getDefaultTtlSeconds();

        // Step 1: Lock Product (SELECT ... FOR UPDATE)
        Product product = productRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found: " + request.getSku()));

        // Step 2: Expire stale reservations for this SKU (inline, under lock)
        expireStaleForProduct(product, now);

        // Step 3: Check available stock
        if (product.getAvailableStock() < request.getQuantity()) {
            throw new InsufficientStockException(
                    "Requested quantity " + request.getQuantity()
                    + " exceeds available stock " + product.getAvailableStock());
        }

        // Step 4: Purchase-limit check (SKU-serialized by product lock)
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

        // Step 4a: Anti-hoarding — max active reservations per user (all SKUs)
        if (props.getMaxActiveReservationsPerUser() > 0) {
            int activeCount = reservationRepository.countActiveReservationsByUser(
                    request.getUserId(), now);
            if (activeCount >= props.getMaxActiveReservationsPerUser()) {
                throw new HoardingLimitExceededException(
                        "User " + request.getUserId()
                        + " has " + activeCount + " active reservations (max "
                        + props.getMaxActiveReservationsPerUser() + ")");
            }
        }

        // Step 4b: Anti-hoarding — max total reserved quantity per user (all SKUs)
        if (props.getMaxTotalReservedQuantityPerUser() > 0) {
            int totalQty = reservationRepository.sumActiveReservedQuantityByUser(
                    request.getUserId(), now);
            if (totalQty + request.getQuantity() > props.getMaxTotalReservedQuantityPerUser()) {
                throw new HoardingLimitExceededException(
                        "User " + request.getUserId()
                        + " would have " + (totalQty + request.getQuantity())
                        + " total reserved units (max "
                        + props.getMaxTotalReservedQuantityPerUser() + ")");
            }
        }

        // Step 4c: Anti-hoarding — cooldown after cancel (same user+SKU)
        if (props.getCooldownSecondsAfterCancel() > 0) {
            var recentCancelled = reservationRepository.findRecentCancelledByUserAndSku(
                    request.getUserId(), request.getSku(), PageRequest.of(0, 1));
            if (!recentCancelled.isEmpty()) {
                Reservation lastCancelled = recentCancelled.get(0);
                Instant cooldownUntil = lastCancelled.getUpdatedAt()
                        .plusSeconds(props.getCooldownSecondsAfterCancel());
                if (now.isBefore(cooldownUntil)) {
                    throw new HoardingLimitExceededException(
                            "User " + request.getUserId()
                            + " must wait until " + cooldownUntil
                            + " before reserving " + request.getSku()
                            + " again (cooldown: "
                            + props.getCooldownSecondsAfterCancel() + "s)");
                }
            }
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
    // LOCK ORDER: Product → Reservation (canonical, no deadlock).
    // Step 1: Read reservation preview (no lock) to discover SKU.
    // Step 2: Lock Product.
    // Step 3: Lock Reservation.
    // Now both are locked in the safe order.

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse confirm(String reservationId) {
        Instant now = clock.instant();

        // Step 1: Preview read (no lock) — discover the SKU
        Reservation preview = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Lock Product FIRST (canonical lock order)
        Product product = productRepository.findBySkuForUpdate(preview.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + preview.getSku()));

        // Step 3: Lock Reservation SECOND
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation disappeared: " + reservationId));

        // Step 4: Validate (when we reach here, we've got the canonical lock for
        // confirm to run exclusively, i.e. the lock info in the Product row
        // physically protects us from concurrent updates, although typically
        // the idempotency guard in step 5 catches double confirm before
        // anything else occurs.)

        // Idempotency — already confirmed
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.debug("Reservation {} already confirmed", reservationId);
            return ReservationResponse.from(reservation);
        }

        // State validation
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot confirm reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 5: Expiry check (under both locks)
        // If expired, transition to EXPIRED and reject — stock releases inline
        if (!reservation.getExpiresAt().isAfter(now)) {
            releaseStock(product, reservation);
            throw new InvalidStateTransitionException(
                    "Reservation " + reservationId + " has expired");
        }

        // Step 6: Expire other stale reservations for this SKU
        expireStaleForProductExcluding(product, now, reservationId);

        // Step 7: Transfer reserved → sold
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
    // Same canonical lock order: Product → Reservation.

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse cancel(String reservationId) {
        Instant now = clock.instant();

        // Step 1: Preview read (no lock) — discover the SKU
        Reservation preview = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Step 2: Lock Product FIRST
        Product product = productRepository.findBySkuForUpdate(preview.getSku())
                .orElseThrow(() -> new RuntimeException(
                        "Product not found: " + preview.getSku()));

        // Step 3: Lock Reservation SECOND
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation disappeared: " + reservationId));

        // Idempotency — already cancelled
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.debug("Reservation {} already cancelled", reservationId);
            return ReservationResponse.from(reservation);
        }

        // State validation
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel reservation " + reservationId
                    + " with status " + reservation.getStatus());
        }

        // Step 4: If expired, expire it instead (release stock once, no cancel)
        if (!reservation.getExpiresAt().isAfter(now)) {
            releaseStock(product, reservation);
            return ReservationResponse.from(reservation);
        }

        // Step 5: Expire other stale reservations
        expireStaleForProductExcluding(product, now, reservationId);

        // Step 6: Release reserved → available (NOT sold — can't cancel a sale)
        product.setReservedStock(product.getReservedStock() - reservation.getQuantity());
        product.setAvailableStock(product.getAvailableStock() + reservation.getQuantity());
        productRepository.save(product);

        // Step 7: Update status
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // Step 8: Record event
        eventRepository.save(new ReservationEvent(reservationId, "CANCELLED"));

        log.info("Reservation cancelled: id={}, qty={}", reservationId, reservation.getQuantity());
        return ReservationResponse.from(reservation);
    }

    // =========================================================================
    // M3: Get Reservation (lazy expiry via ExpiryService proxy)
    // =========================================================================

    // No @Transactional — expireById() is REQUIRES_NEW. Each repo call
    // auto-commits. The re-read after expiry sees latest committed state.
    public ReservationResponse getReservation(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found: " + reservationId));

        // Lazy expiry via separate bean (proxy-safe)
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
    // M5: Inventory Reporting (lazy expiry via ExpiryService proxy)
    // =========================================================================

    // No @Transactional — expireForSku() runs in REQUIRES_NEW and commits
    // independently. Each repo call gets an auto-commit implicit tx, so the
    // re-read after expiry always sees the latest committed state.
    public List<InventoryResponse> getAllInventory() {
        return productRepository.findAll().stream().map(p -> {
            expiryService.expireForSku(p.getSku());
            Product refreshed = productRepository.findById(p.getSku()).orElse(p);
            return InventoryResponse.from(refreshed);
        }).collect(Collectors.toList());
    }

    // Same as getAllInventory.
    public InventoryResponse getInventoryBySku(String sku) {
        Product product = productRepository.findById(sku)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product not found: " + sku));

        expiryService.expireForSku(sku);
        Product refreshed = productRepository.findById(sku).orElse(product);
        return InventoryResponse.from(refreshed);
    }

    // =========================================================================
    // Private helpers — called ONLY while holding the Product lock
    // =========================================================================

    private void validateRequest(ReservationRequest request) {
        if (request.getTtlSeconds() != null
                && (request.getTtlSeconds() < 1
                    || request.getTtlSeconds() > props.getMaxTtlSeconds())) {
            throw new InvalidInputException(
                    "ttlSeconds must be between 1 and " + props.getMaxTtlSeconds());
        }
        if (request.getQuantity() < 1
                || request.getQuantity() > props.getMaxQuantityPerReservation()) {
            throw new InvalidInputException(
                    "quantity must be between 1 and " + props.getMaxQuantityPerReservation());
        }
    }

    /**
     * Expire stale reservations for a product.
     * CALLER MUST HOLD the Product lock.
     */
    private void expireStaleForProduct(Product product, Instant now) {
        for (Reservation r : expiryService.findExpiredForSku(product.getSku(), now)) {
            if (r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(product, r);
            }
        }
    }

    private void expireStaleForProductExcluding(Product product, Instant now,
                                                 String excludeId) {
        for (Reservation r : expiryService.findExpiredForSkuExcluding(
                product.getSku(), now, excludeId)) {
            if (r.getStatus() == ReservationStatus.RESERVED) {
                releaseStock(product, r);
            }
        }
    }

    /**
     * Release reserved stock for a single reservation.
     * CALLER MUST HOLD the Product lock.
     * Guard clause prevents double-release (idempotent under lock).
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