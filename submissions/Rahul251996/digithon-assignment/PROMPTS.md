# PROMPTS.md — AI Collaboration Record

## Overview

This project was built iteratively with AI assistance via Claude Code. The initial scaffold (Spring Boot project with basic pom.xml and empty tests) was human-generated. All domain logic, database migrations, service layer, controllers, concurrency handling, and tests were AI-built based on a detailed specification.

## Prompt Summary

### Initial Build Prompt
The master prompt specified:
- Complete PS4 Inventory Reservation System
- MySQL-only (not PostgreSQL)
- Java 21 / Spring Boot 4.1.2 / Maven
- JPA/Hibernate, Flyway MySQL migrations
- JUnit 5 + Testcontainers with MySQL
- Pessimistic locking (SELECT ... FOR UPDATE)
- TTL-based reservation expiry
- Per-user per-SKU purchase limits

### Audit & Optimization Prompt
The audit prompt asked for:
- Full codebase inspection
- Critical bug fixes (self-invocation, unbounded queries)
- Transaction hardening
- Comprehensive test suite (~80 tests)
- Acceptance scenarios A-J
- Concurrency tests with real MySQL

## AI-Assisted Components

| Component | Status | Notes |
|-----------|--------|-------|
| Domain entities | AI-built | Product, Reservation, ReservationEvent, ReservationStatus |
| Flyway migrations | AI-built | V1 (tables), V2 (indexes) |
| Repositories | AI-built | Pessimistic lock queries, SKU-scoped queries |
| InventoryService | AI-built | Reserve, confirm, cancel, get, report |
| ExpiryService | AI-built | Isolated for proxy-aware transactions |
| ExpiryCleanupScheduler | AI-built | Batched, safe across instances |
| InventorySeedService | AI-built | Idempotent, validates input |
| Controllers | AI-built | Thin REST endpoints |
| Exception handling | AI-built | BusinessException hierarchy, GlobalExceptionHandler |
| Config | AI-built | Clock, properties, RequestIdFilter |
| DTOs | AI-built | Request/Response with Bean Validation |
| Integration tests | AI-built | ~50 tests covering M2-M5 |
| Concurrency tests | AI-built | 100-user, limited-stock, same-user tests |
| API tests | AI-built | 20+ HTTP-level tests with TestRestTemplate |
| Docker config | AI-built | docker-compose.yml, Dockerfile, .env.example |
| Documentation | AI-built | README, CODE_AUDIT, TEST_PLAN, PROMPTS |

## Bugs Fixed During Audit

1. **Spring AOP self-invocation** (Critical): `@Transactional` annotations bypassed on `this.*` calls. Fixed by extracting to `ExpiryService`.
2. **Unbounded expiry queries** (Critical): `findExpiredReservations()` scanned all SKUs. Fixed with `findExpiredForSku()`.
3. **Duplicate expiry logic**: Consolidated into `ExpiryService`.
4. **JDBC URL encoding**: Fixed `characterEncoding=utf8mb4` → `UTF-8` (Java charset name).
5. **Dangling `@Retryable` dependencies**: Removed unused spring-retry and spring-aspects.

## Test Verification

All tests verify actual MySQL database state (not just HTTP responses or mocks). They use:
- Testcontainers MySQL 8.0
- Injectable `FixedClock` (no Thread.sleep)
- `CountDownLatch` for concurrent test synchronization
- Post-operation inventory conservation assertions

## Remaining Limitations

1. No retry for transient DB deadlocks/lock timeouts
2. No idempotency key for reservation creation
3. No multi-instance integration tests (single-process only)
4. No fault-injection (mid-transaction failure simulation)
5. No authentication/authorization
6. Performance benchmarks not run (requires operational MySQL instance)