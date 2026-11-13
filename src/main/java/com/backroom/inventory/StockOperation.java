package com.backroom.inventory;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "stock_operations")
class StockOperation extends TenantRecord {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    StockOperationType type;

    @Column(nullable = false)
    UUID itemId;

    @Column(nullable = false)
    UUID actorId;

    @Column(nullable = false, length = 100)
    String actorName;

    @Column(nullable = false)
    UUID operationKey;

    @Column(nullable = false, length = 64)
    String payloadHash;

    @Column(length = 120)
    String reference;

    @Column(length = 500)
    String note;

    UUID reversesOperationId;

    protected StockOperation() {}

    StockOperation(
            UUID orgId,
            StockOperationType type,
            UUID itemId,
            UUID actorId,
            String actorName,
            UUID operationKey,
            String payloadHash,
            String reference,
            String note,
            UUID reversesOperationId) {
        this.organizationId = orgId;
        this.type = type;
        this.itemId = itemId;
        this.actorId = actorId;
        this.actorName = actorName;
        this.operationKey = operationKey;
        this.payloadHash = payloadHash;
        this.reference = reference;
        this.note = note;
        this.reversesOperationId = reversesOperationId;
    }
}
