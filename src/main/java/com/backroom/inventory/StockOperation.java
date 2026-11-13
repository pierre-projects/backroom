package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
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
    private StockOperationType type;

    @Column(nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private UUID actorId;

    @Column(nullable = false, length = 100)
    private String actorName;

    @Column(nullable = false)
    private UUID operationKey;

    @Column(nullable = false, length = 64)
    private String payloadHash;

    @Column(length = 120)
    private String reference;

    @Column(length = 500)
    private String note;

    private UUID reversesOperationId;

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

    StockOperationType getType() {
        return type;
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getActorId() {
        return actorId;
    }

    String getActorName() {
        return actorName;
    }

    UUID getOperationKey() {
        return operationKey;
    }

    String getPayloadHash() {
        return payloadHash;
    }

    String getReference() {
        return reference;
    }

    String getNote() {
        return note;
    }

    UUID getReversesOperationId() {
        return reversesOperationId;
    }
}
