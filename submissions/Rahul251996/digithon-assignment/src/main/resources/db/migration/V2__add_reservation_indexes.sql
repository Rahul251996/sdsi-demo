-- V2: Additional indexes for performance optimization

-- Compound index for efficient expiry cleanup queries (batch processing)
-- Queries filter by status='RESERVED' and expires_at <= NOW(6)
CREATE INDEX idx_reservations_status_expires_compound
    ON reservations (status, expires_at, sku);

-- Index for efficient user+SKU aggregation queries (purchase limit checks)
CREATE INDEX idx_reservations_user_sku_status
    ON reservations (user_id, sku, status);

-- Covering index for inventory reporting queries
CREATE INDEX idx_products_inventory
    ON products (sku, available_stock, reserved_stock, sold_stock, total_stock);