# PERFORMANCE — Inventory Reservation System

## Complexity Analysis

### Reserve

| Aspect | Before | After | Method |
|--------|--------|-------|--------|
| DB queries | 4 (lock product, sum confirmed, sum reserved, insert) | 4 (same) | Optimized indexes |
| Memory | O(all-expired) across all SKUs | O(expired-for-this-SKU) | `findExpiredForSku` |
| Locks | 1 row lock (product) | 1 row lock (product) | Acceptable serialization |
| Index usage | Full scan for expired | Compound index (status, expires_at, sku) | V2 migration |

### Confirm

| Aspect | Before | After | Method |
|--------|--------|-------|--------|
| DB queries | 5 | 5 | Lock resv → lock product → update both |
| Expiry check | In-memory (expires_at) | In-memory (expires_at) | O(1) comparison |
| Idempotency | Guard clause | Guard clause | Status check under lock |

### Cancel

| Aspect | Before | After | Method |
|--------|--------|-------|--------|
| DB queries | 5 | 5 | Lock resv → lock product → update both |
| Double-cancel | Guard clause | Guard clause | Status check under lock |

### Expiry Cleanup

| Aspect | Before | After | Method |
|--------|--------|-------|--------|
| Batch size | Unbounded | Configurable (default 100) | PageRequest |
| Per-reservation cost | 4 queries (lock resv, lock product, update both) | 4 queries | Same but repeated |
| Index | Full scan | Compound index | V2 migration |
| Deadlock risk | Resv→Product (wrong order) | Product→Resv (canonical order) | Fixed lock ordering |

### Inventory Report

| Aspect | Before | After | Method |
|--------|--------|-------|--------|
| All products | findAll + per-product expire | findAll + per-product expire (delegated) | Separate bean |
| Single SKU | findById + expire | findById + expire (delegated) | Proxy-safe |

## Query Index Analysis

### Purchase limit check
```sql
SELECT COALESCE(SUM(r.quantity), 0)
FROM reservations r
WHERE r.userId = ? AND r.sku = ? AND r.status = 'RESERVED' AND r.expiresAt > ?
```
**Index:** `idx_reservations_user_sku_status (user_id, sku, status)` covers `user_id`, `sku`, `status` equality filters. The `expires_at` range condition is a residual filter.

### Expiry (SKU-scoped)
```sql
SELECT r FROM Reservation r
WHERE r.status = 'RESERVED' AND r.expiresAt <= ? AND r.sku = ?
```
**Index:** `idx_reservations_status_expires_compound (status, expires_at, sku)` covers all three columns. MySQL uses `status=` (equality), then `expires_at<=` (range), then `sku=` (equality) — optimal.

### Expiry (batch, all SKUs)
```sql
SELECT r FROM Reservation r
WHERE r.status = 'RESERVED' AND r.expiresAt <= ?
ORDER BY r.expiresAt ASC
```
**Index:** `idx_reservations_status_expires_compound (status, expires_at, sku)` — the ORDER BY on `expires_at` after `status=` equality uses the second column efficiently.

## Lock Contention Analysis

The product-row pessimistic lock serializes all operations on each SKU. Throughput is bounded by:

```
max_operations_per_second_per_sku = 1 / (transaction_time)
```

With typical transaction time ~5-10ms, each SKU can handle ~100-200 operations/second. This is sufficient for most flash sale scenarios. Under extreme load for a single hot SKU, consider:
1. Sharding by SKU-bucket (e.g., `PHONE-001-1`, `PHONE-001-2` ...)
2. Queue-based reservations with async confirmation

## Concurrency Stress Test Results

Not yet run (requires operational MySQL 8.0). Expected results from the test design:

| Test | Expected |
|------|----------|
| 100 users, 1 unit | 1 success, 99 failures, no overselling |
| 50 users, 5 units | 5 successes, 45 failures |
| 10 threads same user, limit=2 | At most 2 successes |
| Concurrent confirm + cancel | At most 1 succeeds |
| Concurrent reporting + updates | Conservation invariant holds |

Actual results to be inserted after test execution.