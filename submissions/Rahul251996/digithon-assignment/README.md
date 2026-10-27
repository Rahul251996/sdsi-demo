# Inventory Reservation System — Flash Sale

A MySQL-backed inventory reservation system for flash sales. Prevents overselling, enforces per-user purchase limits, supports reservation expiry with TTL, and maintains consistent inventory under concurrent requests.

## Tech Stack

- **Java 21**, **Spring Boot 4.1.2**, **Maven**
- **MySQL 8.0+** with InnoDB
- **Spring Data JPA / Hibernate**
- **Flyway** database migrations (MySQL syntax)
- **JUnit 5**, **Testcontainers** with real MySQL
- **Docker Compose**
- **OpenAPI/Swagger** (springdoc-openapi)

## Requirements Mapping

| Module | Feature | Endpoint |
|--------|---------|----------|
| M1 | Load initial inventory from JSON | Startup hook |
| M2 | Reserve stock with TTL | `POST /api/reservations` |
| M3 | Confirm / Cancel / Get reservation | `POST /api/reservations/{id}/confirm` etc. |
| M4 | Per-user per-SKU purchase limits | Enforced in reserve/confirm |
| M5 | Inventory reporting | `GET /api/inventory`, `GET /api/inventory/{sku}` |

## Architecture

```
controller/     -> Thin REST controllers (ReservationController, InventoryController)
service/        -> Business logic:
                   - InventoryService: reserve, confirm, cancel, get, report
                   - ExpiryService: expiry with proper transactions
                   - InventorySeedService: idempotent JSON seed
domain/         -> JPA entities: Product, Reservation, ReservationEvent, ReservationStatus
repository/     -> Spring Data JPA with pessimistic locking queries
dto/            -> API contracts: Request, Response, ErrorResponse, SeedData
config/         -> Clock (injectable for tests), Properties, RequestIdFilter
scheduler/      -> ExpiryCleanupScheduler (batch cleanup)
exception/      -> BusinessException hierarchy + GlobalExceptionHandler
```

## Database Design

**Tables:** `products`, `reservations`, `reservation_events`

**Engine:** InnoDB

**Isolation:** READ_COMMITTED (with `SELECT ... FOR UPDATE` pessimistic locks)

**Key indexes:**
- `idx_reservations_status_expires_compound` — expiry queries
- `idx_reservations_user_sku_status` — purchase limit queries
- `idx_reservations_sku` — SKU lookups

**Migrations:** `src/main/resources/db/migration/V1__*.sql` through `V2__*.sql`

## MySQL Setup

```bash
# Using Docker Compose
docker compose up -d mysql

# Or create the database manually
mysql -u root -p -e "CREATE DATABASE inventory_db; CREATE USER 'inventory_app'@'%' IDENTIFIED BY 'change_me_locally'; GRANT ALL ON inventory_db.* TO 'inventory_app'@'%';"
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `inventory_db` | Database name |
| `DB_USERNAME` | `inventory_app` | DB user |
| `DB_PASSWORD` | `change_me_locally` | DB password |
| `SERVER_PORT` | `8080` | HTTP port |

Copy `.env.example` to `.env` and customize.

## Running

```bash
# Build
./mvnw clean package

# Run with Docker Compose
docker compose up --build

# Run standalone (MySQL must be running)
./mvnw spring-boot:run
```

## API Endpoints

### Create Reservation
```http
POST /api/reservations
Content-Type: application/json

{"userId": "u1", "sku": "A", "quantity": 2}
# Optional: "ttlSeconds": 60
```
→ `201 Created`

### Confirm Reservation
```http
POST /api/reservations/{id}/confirm
```
→ `200 OK`

### Cancel Reservation
```http
POST /api/reservations/{id}/cancel
```
→ `200 OK`

### Get Reservation
```http
GET /api/reservations/{id}
```
→ `200 OK`

### Get Inventory
```http
GET /api/inventory
GET /api/inventory/{sku}
```
→ `200 OK`

## Error Codes

| Status | Error Code | When |
|--------|-----------|------|
| 400 | `VALIDATION_ERROR` | Missing/blank/invalid fields |
| 400 | `MALFORMED_REQUEST` | Invalid JSON |
| 404 | `NOT_FOUND` | Unknown SKU or reservation |
| 409 | `INSUFFICIENT_STOCK` | Not enough available stock |
| 409 | `PURCHASE_LIMIT_EXCEEDED` | Per-user per-SKU limit exceeded |
| 409 | `INVALID_STATE_TRANSITION` | Confirm cancelled/expired, etc. |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected error |

## TTL & Expiry

- **Default TTL:** 120 seconds
- **Max TTL:** 3600 seconds (configurable via `reservation.max-ttl-seconds`)
- **Expiry boundary:** `currentTime >= expiresAt` (inclusive)
- **Lazy expiry:** Reservation lookup and inventory reporting trigger expiry
- **Scheduled cleanup:** Background worker every 30s, batched (configurable)

## Purchase Limits

Rule: `confirmedQuantity + activeReservedQuantity <= purchaseLimit` per user per SKU.

- Confirmed purchases count permanently
- Cancelled/expired reservations free capacity
- Different users and SKUs have independent limits
- Concurrent requests for the same user+SKU are serialized by the product lock

## MySQL Locking Strategy

All stock-changing operations acquire a **pessimistic write lock** (`SELECT ... FOR UPDATE`) on the `products` row for the relevant SKU. This serializes all operations on that SKU and prevents:

1. **Overselling**: Two threads cannot simultaneously read `availableStock`, both see enough, and both deduct.
2. **Purchase-limit bypass**: Two threads for the same user see each other's committed reservations before their limit check.

Lock ordering is consistent across all operations: product first, reservation second (when needed).

## Running Tests

```bash
# All tests (needs Docker for Testcontainers MySQL)
./mvnw test

# Skip concurrency tests (faster)
./mvnw test -Dtest='!ConcurrencyTest'

# Specific test
./mvnw test -Dtest=AcceptanceTests
```

## Known Limitations

1. **No idempotency key**: Retrying a reservation POST after a network timeout could create a duplicate. A client-supplied `idempotencyKey` with a DB unique constraint would solve this.
2. **No authentication**: `userId` is passed in the request body without verification. Production would need auth middleware.
3. **Single-product lock contention**: Under extreme concurrency (1000s of concurrent requests for the *same* SKU), the product-row lock becomes a serialization point. This is correct but limits throughput for a single hot SKU.
4. **No read-replica separation**: All reads hit the primary. For read-heavy workloads, a read replica for inventory reporting would help.
5. **Spring retry not wired**: Transient deadlock/lock-timeout errors are not retried. The product-lock-first approach minimizes deadlocks but doesn't eliminate them.