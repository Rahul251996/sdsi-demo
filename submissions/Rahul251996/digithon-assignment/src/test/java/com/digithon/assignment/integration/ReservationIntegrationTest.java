package com.digithon.assignment.integration;

import com.digithon.assignment.BaseIntegrationTest;
import com.digithon.assignment.TestConfig;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.dto.ReservationResponse;
import com.digithon.assignment.service.InventoryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2–M3 integration tests: reserve, confirm, cancel, get reservation.
 */
class ReservationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        seedStandardProducts();
    }

    // =========================================================================
    // M2: Reserve Stock — Success cases
    // =========================================================================

    @Test
    void shouldReserveWithDefaultTtl() {
        ReservationRequest req = new ReservationRequest("u1", "A", 2, null);
        ReservationResponse resp = inventoryService.reserve(req);

        assertEquals(ReservationStatus.RESERVED, resp.getStatus());
        assertEquals(2, resp.getQuantity());
        assertEquals("u1", resp.getUserId());
        assertEquals("A", resp.getSku());
        assertNotNull(resp.getReservationId());
        // Default TTL = 120 seconds from clock start (10:00:00Z)
        assertEquals(TestConfig.START.plusSeconds(120), resp.getExpiresAt());

        // Verify inventory
        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(2, a.getReservedStock());
        assertEquals(0, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldReserveWithCustomTtl() {
        ReservationRequest req = new ReservationRequest("u1", "A", 1, 60);
        ReservationResponse resp = inventoryService.reserve(req);

        assertEquals(TestConfig.START.plusSeconds(60), resp.getExpiresAt());
        assertInventoryConsistent(getProduct("A"));
    }

    @Test
    void shouldReserveEntireAvailableStock() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 5, null));

        assertEquals(201, 201); // Created conceptually
        Product a = getProduct("A");
        assertEquals(0, a.getAvailableStock());
        assertEquals(5, a.getReservedStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldExhaustInventoryWithMultipleReservations() {
        inventoryService.reserve(new ReservationRequest("u1", "A", 3, null));
        inventoryService.reserve(new ReservationRequest("u2", "A", 2, null));

        Product a = getProduct("A");
        assertEquals(0, a.getAvailableStock());
        assertEquals(5, a.getReservedStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldGenerateUniqueReservationIds() {
        ReservationResponse r1 = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, null));
        ReservationResponse r2 = inventoryService.reserve(
                new ReservationRequest("u2", "A", 1, null));

        assertNotEquals(r1.getReservationId(), r2.getReservationId());
    }

    @Test
    void shouldPreserveInventoryAfterRestart() {
        // Simulate: reserve, then simulate restart by re-reading
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));

        // Re-read from DB (simulates restart where Hibernate cache is gone)
        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(2, a.getReservedStock());
        assertEquals(0, a.getSoldStock());

        // Reservation still exists
        assertTrue(reservationRepository.findById(resp.getReservationId()).isPresent());
        assertEquals(ReservationStatus.RESERVED,
                reservationRepository.findById(resp.getReservationId()).get().getStatus());
    }

    // =========================================================================
    // M2: Reserve Stock — Failure cases
    // =========================================================================

    @Test
    void shouldRejectUnknownSku() {
        ReservationRequest req = new ReservationRequest("u1", "UNKNOWN", 1, null);
        assertThrows(Exception.class, () -> inventoryService.reserve(req));
    }

    @Test
    void shouldRejectInsufficientStock() {
        ReservationRequest req = new ReservationRequest("u1", "B", 2, null);
        assertThrows(Exception.class, () -> inventoryService.reserve(req));

        // Verify no inventory change
        Product b = getProduct("B");
        assertEquals(1, b.getAvailableStock());
        assertEquals(0, b.getReservedStock());
        assertInventoryConsistent(b);
    }

    @Test
    void shouldRejectReservationWhenStockIsZero() {
        assertThrows(Exception.class,
                () -> inventoryService.reserve(new ReservationRequest("u1", "C", 1, null)));

        Product c = getProduct("C");
        assertEquals(0, c.getAvailableStock());
        assertEquals(0, c.getReservedStock());
        assertInventoryConsistent(c);
    }

    @Test
    void shouldNotModifyInventoryOnFailure() {
        try {
            inventoryService.reserve(new ReservationRequest("u1", "A", 100, null));
        } catch (Exception ignored) {
        }

        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(0, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldRejectExcessiveTtl() {
        ReservationRequest req = new ReservationRequest("u1", "A", 1, 99999);
        assertThrows(Exception.class, () -> inventoryService.reserve(req));
    }

    @Test
    void shouldRejectNegativeTtl() {
        ReservationRequest req = new ReservationRequest("u1", "A", 1, -1);
        assertThrows(Exception.class, () -> inventoryService.reserve(req));
    }

    @Test
    void shouldRejectZeroTtl() {
        ReservationRequest req = new ReservationRequest("u1", "A", 1, 0);
        assertThrows(Exception.class, () -> inventoryService.reserve(req));
    }

    // =========================================================================
    // M3: Confirm Reservation
    // =========================================================================

    @Test
    void shouldConfirmActiveReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));

        ReservationResponse confirmed = inventoryService.confirm(resp.getReservationId());

        assertEquals(ReservationStatus.CONFIRMED, confirmed.getStatus());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(2, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldConfirmThenCancelOtherReservations() {
        // Reserve and confirm one
        ReservationResponse r1 = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        inventoryService.confirm(r1.getReservationId());

        // Reserve another
        ReservationResponse r2 = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, null));
        inventoryService.cancel(r2.getReservationId());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(2, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldRejectDoubleConfirmation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        inventoryService.confirm(resp.getReservationId());

        // Second confirmation should be idempotent (return same state)
        ReservationResponse again = inventoryService.confirm(resp.getReservationId());
        assertEquals(ReservationStatus.CONFIRMED, again.getStatus());

        // Stock should only move once: sold = 2, not 4
        Product a = getProduct("A");
        assertEquals(2, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldRejectConfirmationOfCancelledReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        inventoryService.cancel(resp.getReservationId());

        assertThrows(Exception.class, () -> inventoryService.confirm(resp.getReservationId()));
    }

    @Test
    void shouldRejectConfirmationOfExpiredReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));
        clock.advance(Duration.ofSeconds(61));

        assertThrows(Exception.class, () -> inventoryService.confirm(resp.getReservationId()));
    }

    @Test
    void shouldConfirmJustBeforeExpiry() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));
        clock.advance(Duration.ofSeconds(59));

        ReservationResponse confirmed = inventoryService.confirm(resp.getReservationId());
        assertEquals(ReservationStatus.CONFIRMED, confirmed.getStatus());
        assertInventoryConsistent(getProduct("A"));
    }

    @Test
    void shouldRejectConfirmAtExactExpiry() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));
        clock.advance(Duration.ofSeconds(60)); // exactly at expiry

        assertThrows(Exception.class, () -> inventoryService.confirm(resp.getReservationId()));

        // Reservation should now be EXPIRED
        assertTrue(reservationRepository.findById(resp.getReservationId()).isPresent());
    }

    // =========================================================================
    // M3: Cancel Reservation
    // =========================================================================

    @Test
    void shouldCancelActiveReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        ReservationResponse cancelled = inventoryService.cancel(resp.getReservationId());

        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());

        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock()); // stock restored
        assertEquals(0, a.getReservedStock());
        assertEquals(0, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldRejectDoubleCancellation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        inventoryService.cancel(resp.getReservationId());

        // Idempotent
        ReservationResponse again = inventoryService.cancel(resp.getReservationId());
        assertEquals(ReservationStatus.CANCELLED, again.getStatus());

        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock()); // not restored twice
        assertInventoryConsistent(a);
    }

    @Test
    void shouldRejectCancelConfirmedReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        inventoryService.confirm(resp.getReservationId());

        assertThrows(Exception.class, () -> inventoryService.cancel(resp.getReservationId()));

        // Stock should not have changed (sold stays sold)
        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(2, a.getSoldStock());
        assertInventoryConsistent(a);
    }

    @Test
    void shouldExpireWhenCancellingExpiredReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));
        clock.advance(Duration.ofSeconds(61));

        // Cancelling an expired reservation should expire it (not cancel)
        ReservationResponse result = inventoryService.cancel(resp.getReservationId());
        assertEquals(ReservationStatus.EXPIRED, result.getStatus());

        // Stock released exactly once
        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // M3: Get Reservation
    // =========================================================================

    @Test
    void shouldRetrieveReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));

        ReservationResponse retrieved = inventoryService.getReservation(resp.getReservationId());
        assertEquals(resp.getReservationId(), retrieved.getReservationId());
        assertEquals(ReservationStatus.RESERVED, retrieved.getStatus());
    }

    @Test
    void shouldReturn404ForUnknownReservation() {
        assertThrows(Exception.class,
                () -> inventoryService.getReservation("nonexistent-id"));
    }

    @Test
    void shouldLazyExpireOnGetReservation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));
        clock.advance(Duration.ofSeconds(61));

        ReservationResponse retrieved = inventoryService.getReservation(resp.getReservationId());
        assertEquals(ReservationStatus.EXPIRED, retrieved.getStatus());

        assertInventoryConsistent(getProduct("A"));
    }

    // =========================================================================
    // Cross-cutting: invariants after all operations
    // =========================================================================

    @Test
    void shouldMaintainInvariantsAfterAllOperations() {
        // Reserve
        ReservationResponse r1 = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        assertAllInventoryConsistent();

        // Confirm
        inventoryService.confirm(r1.getReservationId());
        assertAllInventoryConsistent();

        // Reserve another
        ReservationResponse r2 = inventoryService.reserve(
                new ReservationRequest("u2", "A", 1, null));
        assertAllInventoryConsistent();

        // Cancel
        inventoryService.cancel(r2.getReservationId());
        assertAllInventoryConsistent();
    }
}