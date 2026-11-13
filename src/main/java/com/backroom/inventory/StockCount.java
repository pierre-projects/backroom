package com.backroom.inventory;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "stock_counts")
class StockCount extends TenantRecord {
    @Column(nullable = false)
    UUID operationId;

    @Column(nullable = false)
    UUID itemId;

    @Column(nullable = false)
    UUID locationId;

    @Column(nullable = false, precision = 15, scale = 3)
    BigDecimal countedQuantity;

    @Column(nullable = false, precision = 15, scale = 3)
    BigDecimal recordedQuantity;

    protected StockCount() {}

    StockCount(
            UUID orgId,
            UUID operationId,
            UUID itemId,
            UUID locationId,
            BigDecimal countedQuantity,
            BigDecimal recordedQuantity) {
        this.organizationId = orgId;
        this.operationId = operationId;
        this.itemId = itemId;
        this.locationId = locationId;
        this.countedQuantity = countedQuantity;
        this.recordedQuantity = recordedQuantity;
    }
}
