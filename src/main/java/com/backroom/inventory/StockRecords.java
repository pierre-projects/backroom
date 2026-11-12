package com.backroom.inventory;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Persistence records for stock. Balances change only through StockService; history rows are insert-only. */
final class StockRecords {
    private StockRecords() {}
}

@Entity
@Table(name="stock_balances")
class StockBalance extends TenantRecord {
    @Column(nullable=false, updatable=false) UUID itemId;
    @Column(nullable=false, updatable=false) UUID locationId;
    @Column(nullable=false, precision=15, scale=3) BigDecimal quantity=BigDecimal.ZERO;
    @Column(nullable=false) Instant updatedAt=Instant.now();
    protected StockBalance() {}
}

@Entity
@Immutable
@Table(name="stock_operations")
class StockOperation extends TenantRecord {
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) StockOperationType type;
    @Column(nullable=false) UUID itemId;
    @Column(nullable=false) UUID actorId;
    @Column(nullable=false, length=100) String actorName;
    @Column(nullable=false) UUID operationKey;
    @Column(nullable=false, length=64) String payloadHash;
    @Column(length=120) String reference;
    @Column(length=500) String note;
    UUID reversesOperationId;
    protected StockOperation() {}
    StockOperation(UUID orgId, StockOperationType type, UUID itemId, UUID actorId, String actorName, UUID operationKey,
                   String payloadHash, String reference, String note, UUID reversesOperationId) {
        this.organizationId=orgId; this.type=type; this.itemId=itemId; this.actorId=actorId; this.actorName=actorName;
        this.operationKey=operationKey; this.payloadHash=payloadHash; this.reference=reference; this.note=note;
        this.reversesOperationId=reversesOperationId;
    }
}

@Entity
@Immutable
@Table(name="stock_movements")
class StockMovement extends TenantRecord {
    @Column(nullable=false) UUID operationId;
    @Column(nullable=false) UUID itemId;
    @Column(nullable=false) UUID locationId;
    @Column(nullable=false, precision=15, scale=3) BigDecimal quantityChange;
    @Column(nullable=false, precision=15, scale=3) BigDecimal balanceAfter;
    protected StockMovement() {}
    StockMovement(UUID orgId, UUID operationId, UUID itemId, UUID locationId, BigDecimal quantityChange, BigDecimal balanceAfter) {
        this.organizationId=orgId; this.operationId=operationId; this.itemId=itemId; this.locationId=locationId;
        this.quantityChange=quantityChange; this.balanceAfter=balanceAfter;
    }
}

@Entity
@Immutable
@Table(name="stock_counts")
class StockCount extends TenantRecord {
    @Column(nullable=false) UUID operationId;
    @Column(nullable=false) UUID itemId;
    @Column(nullable=false) UUID locationId;
    @Column(nullable=false, precision=15, scale=3) BigDecimal countedQuantity;
    @Column(nullable=false, precision=15, scale=3) BigDecimal recordedQuantity;
    protected StockCount() {}
    StockCount(UUID orgId, UUID operationId, UUID itemId, UUID locationId, BigDecimal countedQuantity, BigDecimal recordedQuantity) {
        this.organizationId=orgId; this.operationId=operationId; this.itemId=itemId; this.locationId=locationId;
        this.countedQuantity=countedQuantity; this.recordedQuantity=recordedQuantity;
    }
}
