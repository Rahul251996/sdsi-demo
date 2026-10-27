-- V1: Create inventory tables for the flash sale reservation system
-- Target: MySQL 8.0+ with InnoDB engine
-- All timestamps stored in UTC

CREATE TABLE IF NOT EXISTS products (
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    total_stock INT NOT NULL,
    available_stock INT NOT NULL,
    reserved_stock INT NOT NULL,
    sold_stock INT NOT NULL,
    purchase_limit INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reservations (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_reservations_product FOREIGN KEY (sku) REFERENCES products(sku),
    INDEX idx_reservations_sku (sku),
    INDEX idx_reservations_status_expires (status, expires_at),
    INDEX idx_reservations_user_sku (user_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Audit/event log for reservation state transitions
CREATE TABLE IF NOT EXISTS reservation_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_reservation_events_reservation (reservation_id),
    INDEX idx_reservation_events_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;