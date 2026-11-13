package com.backroom.restocking;

import com.backroom.shared.TenantRecord;
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
    UUID requestId;

    @Column(nullable = false)
    UUID stockOperationId;

    @Column(nullable = false, precision = 15, scale = 3)
    BigDecimal quantity;

    @Column(nullable = false)
    UUID receiverId;

    @Column(nullable = false, length = 100)
    String receiverName;

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
}
