package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
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
    private UUID itemId;

    @Column(nullable = false, updatable = false)
    private UUID locationId;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected StockBalance() {}

    /** Only StockService calls this, after locking the row and checking the result is not negative. */
    void changeQuantity(BigDecimal after) {
        quantity = after;
        updatedAt = Instant.now();
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    BigDecimal getQuantity() {
        return quantity;
    }
}
