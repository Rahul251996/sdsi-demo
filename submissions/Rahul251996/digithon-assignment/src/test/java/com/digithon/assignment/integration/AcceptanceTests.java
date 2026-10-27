package com.digithon.assignment.integration;

import com.digithon.assignment.BaseIntegrationTest;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.domain.ReservationStatus;
import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.dto.ReservationResponse;
import com.digithon.assignment.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mandatory acceptance scenarios A–J from the specification.
 * Fixture: A(5,2), B(1,1), C(0,1)
 */
class AcceptanceTests extends BaseIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        seedStandardProducts();
    }

    // =========================================================================
    // Test A: Successful reservation
    // =========================================================================

    @Test
    void acceptanceTestA_SuccessfulReservation() {
        ReservationRequest req = new ReservationRequest("u1", "A", 2, null);
        ReservationResponse resp = inventoryService.reserve(req);

        assertEquals(ReservationStatus.RESERVED, resp.getStatus());
        assertEquals(TestConfig.START.plusSeconds(120), resp.getExpiresAt());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock(), "available should be 3");
        assertEquals(2, a.getReservedStock(), "reserved should be 2");
        assertEquals(0, a.getSoldStock(), "sold should be 0");
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Test B: Insufficient stock
    // =========================================================================

    @Test
    void acceptanceTestB_InsufficientStock() {
        ReservationRequest req = new ReservationRequest("u1", "B", 2, null);

        assertThrows(Exception.class, () -> inventoryService.reserve(req));

        Product b = getProduct("B");
        assertEquals(1, b.getAvailableStock(), "available should remain 1");
        assertEquals(0, b.getReservedStock(), "reserved should remain 0");
        assertEquals(0, b.getSoldStock(), "sold should remain 0");
        assertInventoryConsistent(b);
    }

    // =========================================================================
    // Test C: Purchase limit
    // =========================================================================

    @Test
    void acceptanceTestC_PurchaseLimit() {
        // Reserve 2 units for u1 (at limit)
        inventoryService.reserve(new ReservationRequest("u1", "A", 2, null));

        // Attempt another reservation for same user/SKU
        assertThrows(Exception.class,
                () -> inventoryService.reserve(new ReservationRequest("u1", "A", 1, null)));

        // No additional stock reserved
        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock(), "Only first reservation consumed stock");
        assertEquals(2, a.getReservedStock());
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Test D: Confirmation
    // =========================================================================

    @Test
    void acceptanceTestD_Confirmation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        ReservationResponse confirmed = inventoryService.confirm(resp.getReservationId());

        assertEquals(ReservationStatus.CONFIRMED, confirmed.getStatus());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock(), "available=3");
        assertEquals(0, a.getReservedStock(), "reserved=0");
        assertEquals(2, a.getSoldStock(), "sold=2");
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Test E: Cancellation
    // =========================================================================

    @Test
    void acceptanceTestE_Cancellation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        ReservationResponse cancelled = inventoryService.cancel(resp.getReservationId());

        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());

        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock(), "available=5");
        assertEquals(0, a.getReservedStock(), "reserved=0");
        assertEquals(0, a.getSoldStock(), "sold=0");
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Test F: Expiration
    // =========================================================================

    @Test
    void acceptanceTestF_Expiration() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 60));

        // Advance clock by 60 seconds
        clock.advance(Duration.ofSeconds(60));

        // Lazy-expire via get
        ReservationResponse retrieved = inventoryService.getReservation(resp.getReservationId());

        // Should be EXPIRED
        assertEquals(ReservationStatus.EXPIRED, retrieved.getStatus());

        // Stock restored exactly once
        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock(), "stock restored");
        assertEquals(0, a.getReservedStock());
        assertInventoryConsistent(a);

        // Confirmation should be rejected
        assertThrows(Exception.class,
                () -> inventoryService.confirm(resp.getReservationId()));
    }

    // =========================================================================
    // Test G: Last-unit concurrency (implemented in ConcurrencyTest)
    // =========================================================================
    // @see ConcurrencyTest#shouldNotOversellLastUnitWith100ConcurrentUsers()

    // =========================================================================
    // Test H: Same-user concurrency (implemented in ConcurrencyTest)
    // =========================================================================
    // @see ConcurrencyTest#shouldNotExceedPurchaseLimitForSameUserConcurrently()

    // =========================================================================
    // Test I: Repeated confirmation
    // =========================================================================

    @Test
    void acceptanceTestI_RepeatedConfirmation() {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));

        inventoryService.confirm(resp.getReservationId());
        // Second confirmation — idempotent
        inventoryService.confirm(resp.getReservationId());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(2, a.getSoldStock(), "Sold quantity increases only once");
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Test J: Transaction rollback (partial verification)
    // =========================================================================

    @Test
    void acceptanceTestJ_TransactionRollback() {
        // Attempt to reserve more than max quantity — should roll back
        try {
            inventoryService.reserve(new ReservationRequest("u1", "A", 99999, null));
        } catch (Exception ignored) {}

        // No partial inventory changes
        Product a = getProduct("A");
        assertEquals(5, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(0, a.getSoldStock());

        // A subsequent valid operation should succeed
        assertDoesNotThrow(() ->
                inventoryService.reserve(new ReservationRequest("u1", "A", 1, null)));

        assertAllInventoryConsistent();
    }
}