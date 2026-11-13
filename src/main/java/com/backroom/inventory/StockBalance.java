package com.backroom.inventory;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Quantity of one item at one location. Changes only through StockService; operations, movements and counts are the
 * insert-only history behind it.
 */
@Entity
@Table(name = "stock_balances")
class StockBalance extends TenantRecord {
    @Column(nullable = false, updatable = false)
    UUID itemId;

    @Column(nullable = false, updatable = false)
    UUID locationId;

    @Column(nullable = false, precision = 15, scale = 3)
    BigDecimal quantity = BigDecimal.ZERO;

    @Column(nullable = false)
    Instant updatedAt = Instant.now();

    protected StockBalance() {}
}
