-- V1: Create inventory tables for the flash sale reservation system
-- Target: MySQL 8.0+ with InnoDB
-- All timestamps stored in UTC

CREATE TABLE products (
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    total_stock INT NOT NULL CHECK (total_stock >= 0),
    available_stock INT NOT NULL CHECK (available_stock >= 0),
    reserved_stock INT NOT NULL CHECK (reserved_stock >= 0),
    sold_stock INT NOT NULL CHECK (sold_stock >= 0),
    purchase_limit INT NOT NULL CHECK (purchase_limit > 0),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku),
    CONSTRAINT chk_inventory_consistency CHECK (total_stock = available_stock + reserved_stock + sold_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE reservations (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_reservations_product FOREIGN KEY (sku) REFERENCES products(sku),
    INDEX idx_reservations_user_sku (user_id, sku),
    INDEX idx_reservations_status_expires (status, expires_at),
    INDEX idx_reservations_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Audit/event log for reservation state transitions
CREATE TABLE reservation_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_reservation_events_reservation (reservation_id),
    INDEX idx_reservation_events_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;