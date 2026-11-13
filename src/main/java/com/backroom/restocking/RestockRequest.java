package com.backroom.restocking;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "restock_requests")
class RestockRequest extends TenantRecord {
    @Column(nullable = false, updatable = false)
    int number;

    @Column(nullable = false, updatable = false)
    UUID itemId;

    @Column(nullable = false, updatable = false)
    UUID locationId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    BigDecimal requestedQuantity;

    @Column(precision = 15, scale = 3)
    BigDecimal approvedQuantity;

    @Column(nullable = false, precision = 15, scale = 3)
    BigDecimal receivedQuantity = BigDecimal.ZERO.setScale(3);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    RequestStatus status = RequestStatus.SUBMITTED;

    @Column(nullable = false, updatable = false)
    UUID requesterId;

    @Column(nullable = false, updatable = false, length = 100)
    String requesterName;

    @Column(updatable = false, length = 500)
    String note;

    UUID decidedBy;

    @Column(length = 100)
    String decidedByName;

    Instant decidedAt;

    @Column(length = 500)
    String decisionNote;

    UUID closedBy;

    @Column(length = 100)
    String closedByName;

    Instant closedAt;

    @Column(length = 500)
    String closeReason;

    @Column(nullable = false)
    Instant updatedAt = Instant.now();

    protected RestockRequest() {}

    RestockRequest(
            UUID orgId,
            int number,
            UUID itemId,
            UUID locationId,
            BigDecimal quantity,
            UUID requesterId,
            String requesterName,
            String note) {
        this.organizationId = orgId;
        this.number = number;
        this.itemId = itemId;
        this.locationId = locationId;
        this.requestedQuantity = quantity;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.note = note;
    }

    BigDecimal remaining() {
        return approvedQuantity == null ? BigDecimal.ZERO : approvedQuantity.subtract(receivedQuantity);
    }
    /** Status after the received quantity changed, for requests that are still open for deliveries. */
    void settle() {
        if (status == RequestStatus.CLOSED_SHORT) return;
        status = receivedQuantity.signum() == 0
                ? RequestStatus.APPROVED
                : receivedQuantity.compareTo(approvedQuantity) >= 0
                        ? RequestStatus.FULFILLED
                        : RequestStatus.PARTIALLY_RECEIVED;
    }
}
