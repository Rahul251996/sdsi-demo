# CODE AUDIT — Inventory Reservation System

Audit date: 2026-10-27

## Summary

Audited an AI-generated Spring Boot 4.1.2 / Java 21 / MySQL 8.0+ inventory reservation system for flash sales. 32 source files, ~1800 LOC.

## Severity Classification

| Severity | Meaning |
|----------|---------|
| Critical | Can cause data corruption, overselling, or lost inventory |
| High | Correctness risk under load; needs fix |
| Medium | Performance/maintainability concern |
| Low | Cosmetic or minor |

---

## Critical Findings (Fixed)

### C1: Self-invocation bypasses @Transactional proxy

**File:** `InventoryService.java`
**Status:** FIXED

`getReservation()` and `getAllInventory()` were `@Transactional(readOnly=true)` but called `this.expireReservationInNewTx()` and `this.expireForReport()` — self-invocations that bypass Spring AOP. The write operations silently ran in read-only transactions, so Hibernate would not flush writes.

**Fix:** Extracted expiry operations into `ExpiryService` (separate bean). Cross-service calls go through the AOP proxy.

### C2: `findExpiredReservations()` had no SKU filter

**File:** `ReservationRepository.java`, `InventoryService.java`
**Status:** FIXED

`expireProductReservationsUnderLock()` called `findExpiredReservations()`, which loaded ALL expired reservations across ALL SKUs, then filtered in Java. O(#expired-reservations) per request.

**Fix:** Added `findExpiredForSku(sku, now)` and `findExpiredForSkuExcluding(sku, now, excludeId)` that use the compound index. Query now returns only SKU-scoped rows.

## High Findings (Fixed)

### H1: Duplicate expiry logic

**Files:** `InventoryService.java`, `ExpiryCleanupScheduler.java`
**Status:** FIXED

Both classes contained separate stock-release logic (`releaseStock` in InventoryService, `expireSingleReservation` in Scheduler). Any change to one needed duplication.

**Fix:** Consolidated into `ExpiryService.java`. Both InventoryService (inline expiry) and Scheduler (batch expiry) delegate to it.

### H2: No retry for transient DB failures

**Status:** Deferred

No retry mechanism for deadlocks or lock-timeout errors. The `RetryHelper` class was created but not wired in. **Note:** This remains a known limitation — the product-lock serialization approach makes deadlocks unlikely since we always lock product-first, but lock timeouts under extreme load could still fail.

---

## Medium Findings

### M1: Purchase-limit race under product lock (False alarm — verified safe)

The product lock serializes all SKU operations. Thread B's `sumActiveReservedQuantity` sees Thread A's committed reservation because Thread A committed before Thread B's `select ... for update` acquired the product lock. The product lock is *sufficient*.

### M2: No idempotency key for reservation creation

**Status:** Not implemented

Retrying a reservation creation (e.g., after a network timeout) could create duplicates. A proper `idempotencyKey` column with a DB unique constraint would solve this. Documented limitation.

### M3: Reporting in read-only transaction calls write operation

**Status:** FIXED (via ExpiryService delegate)

---

## Low Findings

### L1: Unused `expireReservations` bulk-update query

**File:** `ReservationRepository.java`
**Status:** Removed

The `@Modifying @Query("UPDATE Reservation r SET r.status = 'EXPIRED'...")` was a bulk update that doesn't release stock. Not safe to use. Removed in refactored repository.

### L2: `findByUserIdAndSkuForUpdate` not wired into purchase-limit check

**Status:** Not needed

The product lock already serializes per-SKU operations. Locking user/SKU reservation rows is redundant.

---

## Correctness Guarantees

1. **No overselling:** `SELECT ... FOR UPDATE` on the Product row serializes all stock mutations. One thread holds the lock → validates → updates → commits before the next thread reads.
2. **Purchase-limit safety:** The product lock ensures that two threads for the same user+SKU see each other's committed reservations before their own limit checks.
3. **No double-confirm/cancel:** Guard clauses (`if status != RESERVED`) inside the locked section.
4. **No double stock release:** Guard clause (`if status != RESERVED return`) in `releaseStock()`.
5. **Expiry enforced:** `now >= expiresAt` check in confirm/cancel flows, plus lazy expiry in get/add, plus scheduled batch cleanup.
6. **Inventory conservation:** `totalStock = available + reserved + sold` verified after every operation by tests.