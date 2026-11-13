package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "stock_movements")
class StockMovement extends TenantRecord {
    @Column(nullable = false)
    private UUID operationId;

    @Column(nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private UUID locationId;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantityChange;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal balanceAfter;

    protected StockMovement() {}

    StockMovement(
            UUID orgId,
            UUID operationId,
            UUID itemId,
            UUID locationId,
            BigDecimal quantityChange,
            BigDecimal balanceAfter) {
        this.organizationId = orgId;
        this.operationId = operationId;
        this.itemId = itemId;
        this.locationId = locationId;
        this.quantityChange = quantityChange;
        this.balanceAfter = balanceAfter;
    }

    UUID getOperationId() {
        return operationId;
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    BigDecimal getQuantityChange() {
        return quantityChange;
    }

    BigDecimal getBalanceAfter() {
        return balanceAfter;
    }
}
