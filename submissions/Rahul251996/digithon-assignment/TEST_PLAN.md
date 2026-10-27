# TEST PLAN — Inventory Reservation System

## Test Categories

| Category | Location | Count | MySQL Required |
|----------|----------|-------|----------------|
| Unit/Context | `DigithonAssignmentApplicationTests` | 1 | No |
| Integration (reserve/confirm/cancel) | `integration/ReservationIntegrationTest` | 20 | Yes |
| Purchase limits | `integration/PurchaseLimitTest` | 11 | Yes |
| Inventory reporting | `integration/InventoryReportingTest` | 8 | Yes |
| Seed validation | `integration/SeedValidationTest` | 3 | Yes |
| Acceptance A–J | `integration/AcceptanceTests` | 8 | Yes |
| Concurrency | `concurrency/ConcurrencyTest` | 9 | Yes |
| API/HTTP | `api/ReservationApiTest` | 20+ | Yes |

**Total**: ~80 tests

## Test Scenarios Covered

### A. Input Validation (API tests)
- Missing/blank userId → 400
- Missing/blank SKU → 400
- Unknown SKU → 404
- Missing quantity → 400
- Zero/negative/fractional/string quantity → 400
- Excessive quantity → 400
- Malformed JSON → 400
- Empty body → 400
- Non-JSON content type → 4xx
- Invalid TTL (zero, negative, excessive) → 400

### B. Reservation Behavior (Integration)
- Successful reservation with default 120s TTL
- Custom TTL reservation
- Reserve entire available stock
- Reserve more than available → 409
- Reserve when stock is zero → 409
- Multiple reservations exhausting inventory
- Failed requests don't modify inventory
- Repeated seed is idempotent

### C. Confirmation & Cancellation
- Confirm active reservation
- Cancel active reservation
- Double confirmation (idempotent)
- Double cancellation (idempotent)
- Confirm cancelled → error
- Confirm expired → error
- Cancel confirmed → error
- Cancel expired → expire it
- Confirm just before expiry → OK
- Confirm exactly at expiry → expired
- Confirm just after expiry → expired

### D. Purchase Limits
- Within-limit reservation
- Exactly-at-limit reservation
- Exceeding limit → 409
- Multiple active reservations aggregate
- Confirmed purchases count permanently
- Cancelled reservations free capacity
- Expired reservations free capacity
- Independent limits by SKU
- Independent limits by user
- Failed requests don't consume capacity
- Cancel-and-reserve sequence

### E. Concurrency (Real MySQL)
- 100 users, 1 unit → exactly 1 success, 99 failures
- 50 users, 5 units → exactly 5 successes
- 10 threads, same user, limit=2 → at most 2 successes
- Concurrent confirm + cancel → one succeeds, other fails
- Concurrent expiry + confirm → expiry wins
- Concurrent reporting during updates → consistency holds
- Repeated operations → idempotent

### F. Acceptance Scenarios A–J
- A: Successful reservation
- B: Insufficient stock
- C: Purchase limit exceeded
- D: Confirmation
- E: Cancellation
- F: Expiration with clock advance
- G: Last-unit concurrency (100 users, 1 unit)
- H: Same-user concurrency (10 threads, limit=2)
- I: Repeated confirmation
- J: Transaction rollback

### G. Reporting
- All inventory endpoint
- Single SKU endpoint
- Unknown SKU → 404
- Conservation after reserve/confirm/cancel/expiry
- Never negative counters
- Zero-stock product

## Invariants Asserted

After every stock-changing operation:
1. `totalStock = available + reserved + sold`
2. `available >= 0`
3. `reserved >= 0`
4. `sold >= 0`
5. No terminal state transitions back to RESERVED

## Running Tests

```bash
# All tests (requires Docker for Testcontainers)
./mvnw test

# Specific test class
./mvnw test -Dtest=ConcurrencyTest

# Skip concurrency tests (faster)
./mvnw test -Dtest='!ConcurrencyTest'
```

## Known Test Gaps

1. **Transaction rollback injection tests**: Not implemented — would require test-only hooks or proxies to inject failures mid-transaction.
2. **Multiple-app-instance tests**: Single-process tests simulate concurrent threads within one JVM. True multi-instance tests would need separate processes or Docker containers.
3. **Uncertain commit outcome tests**: Complex to simulate in a unit/integration test — the MySQL driver retries on commit failure, and actual commit success/failure is binary.
4. **Connection pool exhaustion**: Requires careful pool configuration and load testing.