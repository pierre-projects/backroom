package com.backroom.restocking;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "restock_receipts")
class RestockReceipt extends TenantRecord {
    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private UUID stockOperationId;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false)
    private UUID receiverId;

    @Column(nullable = false, length = 100)
    private String receiverName;

    protected RestockReceipt() {}

    RestockReceipt(
            UUID orgId,
            UUID requestId,
            UUID stockOperationId,
            BigDecimal quantity,
            UUID receiverId,
            String receiverName) {
        this.organizationId = orgId;
        this.requestId = requestId;
        this.stockOperationId = stockOperationId;
        this.quantity = quantity;
        this.receiverId = receiverId;
        this.receiverName = receiverName;
    }

    UUID getRequestId() {
        return requestId;
    }

    UUID getStockOperationId() {
        return stockOperationId;
    }

    BigDecimal getQuantity() {
        return quantity;
    }

    UUID getReceiverId() {
        return receiverId;
    }

    String getReceiverName() {
        return receiverName;
    }
}
