-- V2: Additional indexes for performance optimization
-- MySQL 8.0+ supports IF NOT EXISTS for indexes

-- Compound index for expiry cleanup queries.
-- Query pattern: WHERE status = 'RESERVED' AND expires_at <= ? AND sku = ?
-- Order: status (equality filter) -> expires_at (range filter) -> sku (equality filter)
CREATE INDEX IF NOT EXISTS idx_reservations_status_expires_compound
    ON reservations (status, expires_at, sku);

-- Index for user+SKU aggregation queries (purchase limit checks).
-- Query pattern: WHERE user_id = ? AND sku = ? AND status = 'RESERVED'|'CONFIRMED'
CREATE INDEX IF NOT EXISTS idx_reservations_user_sku_status
    ON reservations (user_id, sku, status);