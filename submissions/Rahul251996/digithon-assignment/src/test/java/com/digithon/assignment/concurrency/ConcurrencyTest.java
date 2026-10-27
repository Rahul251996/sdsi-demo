package com.digithon.assignment.concurrency;

import com.digithon.assignment.BaseIntegrationTest;
import com.digithon.assignment.domain.Product;
import com.digithon.assignment.dto.ReservationRequest;
import com.digithon.assignment.dto.ReservationResponse;
import com.digithon.assignment.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MySQL concurrency tests using Testcontainers.
 *
 * Tests A–J from the acceptance criteria plus additional edge cases.
 * Each test uses real threads and CountDownLatch synchronization
 * to maximize contention on the database.
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        seedStandardProducts();
    }

    // =========================================================================
    // Helper: run N tasks concurrently, wait for all to finish
    // =========================================================================

    private <T> List<T> runConcurrently(int threadCount, List<Callable<T>> tasks)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(threadCount, 50));
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    // Record as null to indicate failure
                    results.add(null);
                } catch (TimeoutException e) {
                    results.add(null);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Test G: Last-unit concurrency — 100 users, 1 available unit
    // =========================================================================

    @Test
    void shouldNotOversellLastUnitWith100ConcurrentUsers() throws Exception {
        // SKU B: total=1, limit=1
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final String userId = "user-" + i;
            tasks.add(() -> {
                startLatch.await();
                try {
                    inventoryService.reserve(new ReservationRequest(userId, "B", 1, null));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
                return null;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();

        for (Future<Void> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        executor.shutdownNow();

        // Assertions
        assertEquals(1, successCount.get(), "Exactly 1 reservation should succeed");
        assertEquals(THREAD_COUNT - 1, failureCount.get(),
                "All other reservations should fail");

        Product b = getProduct("B");
        assertEquals(0, b.getAvailableStock(), "Available should be 0");
        assertEquals(1, b.getReservedStock(), "Reserved should be 1");
        assertEquals(0, b.getSoldStock(), "Sold should be 0");
        assertEquals(b.getTotalStock(),
                b.getAvailableStock() + b.getReservedStock() + b.getSoldStock(),
                "Inventory conservation invariant");
        assertInventoryConsistent(b);
    }

    // =========================================================================
    // Test H: Same-user concurrency — 10 threads, single user, limit=2
    // =========================================================================

    @Test
    void shouldNotExceedPurchaseLimitForSameUserConcurrently() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        int concurrentRequests = 10;
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            tasks.add(() -> {
                startLatch.await();
                try {
                    inventoryService.reserve(new ReservationRequest("u1", "A", 1, null));
                    successCount.incrementAndGet();
                } catch (Exception ignored) {}
                return null;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();

        for (Future<Void> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdownNow();

        // Purchase limit for SKU A is 2; user u1 cannot reserve > 2 total
        assertTrue(successCount.get() <= 2,
                "At most 2 reservations should succeed for user u1 (purchaseLimit=2), got "
                + successCount.get());

        Product a = getProduct("A");
        int totalReservedForU1 = a.getReservedStock();
        assertTrue(totalReservedForU1 <= 2,
                "At most 2 units should be reserved for SKU A, got " + totalReservedForU1);
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Many concurrent users, limited stock
    // =========================================================================

    @Test
    void shouldNotOversellWithLimitedStockAndManyUsers() throws Exception {
        // SKU A: total=5, limit=2; 50 users each want 1 unit
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final String userId = "user-" + i;
            tasks.add(() -> {
                startLatch.await();
                try {
                    inventoryService.reserve(new ReservationRequest(userId, "A", 1, null));
                    successCount.incrementAndGet();
                } catch (Exception ignored) {}
                return null;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();

        for (Future<Void> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdownNow();

        // At most 5 reservations can succeed (totalStock=5)
        assertEquals(5, successCount.get(),
                "Exactly 5 reservations should succeed for 5 available units");

        Product a = getProduct("A");
        assertEquals(0, a.getAvailableStock(), "Available should be 0");
        assertEquals(5, a.getReservedStock(), "Reserved should be 5");
        assertEquals(0, a.getSoldStock(), "Sold should be 0");
        assertEquals(a.getTotalStock(),
                a.getAvailableStock() + a.getReservedStock() + a.getSoldStock());
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Concurrent confirm + cancel on same reservation
    // =========================================================================

    @Test
    void shouldHandleConcurrentConfirmAndCancelSafely() throws Exception {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        String reservationId = resp.getReservationId();

        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<String> confirmTask = () -> {
            startLatch.await();
            try {
                inventoryService.confirm(reservationId);
                return "CONFIRM_SUCCESS";
            } catch (Exception e) {
                return "CONFIRM_" + e.getClass().getSimpleName();
            }
        };

        Callable<String> cancelTask = () -> {
            startLatch.await();
            try {
                inventoryService.cancel(reservationId);
                return "CANCEL_SUCCESS";
            } catch (Exception e) {
                return "CANCEL_" + e.getClass().getSimpleName();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> confirmFuture = executor.submit(confirmTask);
        Future<String> cancelFuture = executor.submit(cancelTask);
        startLatch.countDown();

        String confirmResult = confirmFuture.get(30, TimeUnit.SECONDS);
        String cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // At least one should succeed, the other should fail with InvalidStateTransition
        assertTrue(
                (confirmResult.equals("CONFIRM_SUCCESS") && cancelResult.contains("InvalidState"))
                || (cancelResult.equals("CANCEL_SUCCESS") && confirmResult.contains("InvalidState")),
                "One should succeed, the other fail. Confirm=" + confirmResult + ", Cancel=" + cancelResult
        );

        // Stock must be consistent: either confirmed (sold=2) or cancelled (available=5)
        Product a = getProduct("A");
        assertInventoryConsistent(a);
        assertTrue(a.getAvailableStock() + a.getReservedStock() + a.getSoldStock()
                == a.getTotalStock());
    }

    // =========================================================================
    // Concurrent expiry + confirm
    // =========================================================================

    @Test
    void shouldHandleConcurrentExpiryAndConfirm() throws Exception {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 1, 5)); // short 5s TTL

        // Advance clock to just before expiry
        clock.advance(Duration.ofSeconds(4));

        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<String> confirmTask = () -> {
            startLatch.await();
            // Advance just past expiry before confirm
            clock.advance(Duration.ofSeconds(2));
            try {
                inventoryService.confirm(resp.getReservationId());
                return "CONFIRMED";
            } catch (Exception e) {
                return "REJECTED:" + e.getClass().getSimpleName();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<String> f = executor.submit(confirmTask);
        startLatch.countDown();
        String result = f.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // After clock advance, expiry time has passed — should be rejected
        assertTrue(result.contains("REJECTED"),
                "Confirm after expiry should be rejected, got: " + result);

        assertInventoryConsistent(getProduct("A"));
    }

    // =========================================================================
    // Concurrent reporting during updates
    // =========================================================================

    @Test
    void shouldReportConsistentlyDuringConcurrentUpdates() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger reportCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();

        // 10 concurrent reservation tasks
        for (int i = 0; i < 10; i++) {
            final String userId = "user-" + i;
            tasks.add(() -> {
                startLatch.await();
                try {
                    inventoryService.reserve(new ReservationRequest(userId, "A", 1, null));
                } catch (Exception ignored) {}
                return null;
            });
        }

        // 5 concurrent inventory report tasks
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                startLatch.await();
                try {
                    var list = inventoryService.getAllInventory();
                    for (var item : list) {
                        assertEquals(item.getTotalStock(),
                                item.getAvailable() + item.getReserved() + item.getSold(),
                                "Conservation invariant violated during concurrent reporting");
                    }
                    reportCount.incrementAndGet();
                } catch (Exception ignored) {}
                return null;
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(15);
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }
        startLatch.countDown();

        for (Future<Void> f : futures) {
            try { f.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdownNow();

        assertTrue(reportCount.get() > 0, "At least some reports should succeed");
        assertAllInventoryConsistent();
    }

    // =========================================================================
    // Concurrent repeated operations don't double-apply
    // =========================================================================

    @Test
    void shouldNotDoubleApplyRepeatedOperations() throws Exception {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));
        String reservationId = resp.getReservationId();

        Product aAfterReserve = getProduct("A");
        assertEquals(3, aAfterReserve.getAvailableStock());
        assertEquals(2, aAfterReserve.getReservedStock());

        // Confirm twice
        inventoryService.confirm(reservationId);
        inventoryService.confirm(reservationId); // should be idempotent

        Product a = getProduct("A");
        assertEquals(2, a.getSoldStock(), "Sold should be 2, not 4 (idempotent confirmation)");
        assertInventoryConsistent(a);
    }

    // =========================================================================
    // Acceptance Test I: Repeated confirmation
    // =========================================================================

    @Test
    void acceptanceTestI_RepeatedConfirmation() throws Exception {
        ReservationResponse resp = inventoryService.reserve(
                new ReservationRequest("u1", "A", 2, null));

        // First confirmation
        inventoryService.confirm(resp.getReservationId());
        // Second confirmation (idempotent)
        inventoryService.confirm(resp.getReservationId());

        Product a = getProduct("A");
        assertEquals(3, a.getAvailableStock());
        assertEquals(0, a.getReservedStock());
        assertEquals(2, a.getSoldStock(), "Sold quantity increases only once");
        assertInventoryConsistent(a);
    }
}