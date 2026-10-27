package com.digithon.assignment.integration;

import com.digithon.assignment.BaseIntegrationTest;
import com.digithon.assignment.dto.InventoryResponse;
import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5: Inventory reporting tests.
 */
class InventoryReportingTest extends BaseIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        seedStandardProducts();
    }

    // =========================================================================
    // Basic reporting
    // =========================================================================

    @Test
    void shouldReportAllProducts() {
        List<InventoryResponse> inventory = inventoryService.getAllInventory();
        assertEquals(3, inventory.size());

        InventoryResponse a = inventory.stream()
                .filter(i -> i.getSku().equals("A")).findFirst().orElseThrow();
        assertEquals("Product A", a.getName());
        assertEquals(5, a.getTotalStock());
        assertEquals(5, a.getAvailable());
        assertEquals(0, a.getReserved());
        assertEquals(0, a.getSold());
        assertEquals(2, a.getPurchaseLimit());
    }

    @Test
    void shouldReportSingleSku() {
        InventoryResponse b = inventoryService.getInventoryBySku("B");
        assertEquals("B", b.getSku());
        assertEquals(1, b.getTotalStock());
        assertEquals(1, b.getAvailable());
    }

    @Test
    void shouldReturn404ForUnknownSku() {
        assertThrows(Exception.class,
                () -> inventoryService.getInventoryBySku("NONEXISTENT"));
    }

    @Test
    void shouldReportConservationAfterReservation() {
        inventoryService.reserve(new ReservationRequest("u1", "A", 2, null));

        InventoryResponse a = inventoryService.getInventoryBySku("A");
        assertEquals(5, a.getTotalStock());
        assertEquals(3, a.getAvailable());
        assertEquals(2, a.getReserved());
        assertEquals(0, a.getSold());
        assertEquals(a.getTotalStock(), a.getAvailable() + a.getReserved() + a.getSold());
    }

    @Test
    void shouldReportConservationAfterConfirmation() {
        var resp = inventoryService.reserve(new ReservationRequest("u1", "A", 2, null));
        inventoryService.confirm(resp.getReservationId());

        InventoryResponse a = inventoryService.getInventoryBySku("A");
        assertEquals(3, a.getAvailable());
        assertEquals(0, a.getReserved());
        assertEquals(2, a.getSold());
        assertEquals(a.getTotalStock(), a.getAvailable() + a.getReserved() + a.getSold());
    }

    @Test
    void shouldReportConservationAfterCancellation() {
        var resp = inventoryService.reserve(new ReservationRequest("u1", "A", 2, null));
        inventoryService.cancel(resp.getReservationId());

        InventoryResponse a = inventoryService.getInventoryBySku("A");
        assertEquals(5, a.getAvailable());
        assertEquals(0, a.getReserved());
        assertEquals(0, a.getSold());
        assertEquals(a.getTotalStock(), a.getAvailable() + a.getReserved() + a.getSold());
    }

    @Test
    void shouldReportConservationAfterExpiry() {
        var resp = inventoryService.reserve(new ReservationRequest("u1", "A", 2, 60));
        clock.advance(Duration.ofSeconds(61));

        // Lazy expire via get
        inventoryService.getReservation(resp.getReservationId());

        InventoryResponse a = inventoryService.getInventoryBySku("A");
        assertEquals(5, a.getAvailable());
        assertEquals(0, a.getReserved());
        assertEquals(0, a.getSold());
        assertEquals(a.getTotalStock(), a.getAvailable() + a.getReserved() + a.getSold());
    }

    @Test
    void shouldNeverReportNegativeCounters() {
        // Try operations that could cause issues
        try {
            inventoryService.reserve(new ReservationRequest("u1", "B", 100, null));
        } catch (Exception ignored) {}

        InventoryResponse b = inventoryService.getInventoryBySku("B");
        assertTrue(b.getAvailable() >= 0);
        assertTrue(b.getReserved() >= 0);
        assertTrue(b.getSold() >= 0);
    }

    @Test
    void shouldReportZeroStockProduct() {
        InventoryResponse c = inventoryService.getInventoryBySku("C");
        assertEquals(0, c.getTotalStock());
        assertEquals(0, c.getAvailable());
        assertEquals(0, c.getReserved());
        assertEquals(0, c.getSold());
    }
}