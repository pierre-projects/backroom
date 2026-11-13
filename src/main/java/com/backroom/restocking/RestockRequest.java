package com.backroom.restocking;

import com.backroom.shared.persistence.TenantRecord;
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
    private int number;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, updatable = false)
    private UUID locationId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 3)
    private BigDecimal requestedQuantity;

    @Column(precision = 15, scale = 3)
    private BigDecimal approvedQuantity;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal receivedQuantity = BigDecimal.ZERO.setScale(3);

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.SUBMITTED;

    @Column(nullable = false, updatable = false)
    private UUID requesterId;

    @Column(nullable = false, updatable = false, length = 100)
    private String requesterName;

    @Column(updatable = false, length = 500)
    private String note;

    private UUID decidedBy;

    @Column(length = 100)
    private String decidedByName;

    private Instant decidedAt;

    @Column(length = 500)
    private String decisionNote;

    private UUID closedBy;

    @Column(length = 100)
    private String closedByName;

    private Instant closedAt;

    @Column(length = 500)
    private String closeReason;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

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
    private void settle() {
        if (status == RequestStatus.CLOSED_SHORT) return;
        status = receivedQuantity.signum() == 0
                ? RequestStatus.APPROVED
                : receivedQuantity.compareTo(approvedQuantity) >= 0
                        ? RequestStatus.FULFILLED
                        : RequestStatus.PARTIALLY_RECEIVED;
    }

    void approve(BigDecimal quantity, UUID actorId, String actorName, String note) {
        approvedQuantity = quantity;
        status = RequestStatus.APPROVED;
        decidedBy = actorId;
        decidedByName = actorName;
        decidedAt = Instant.now();
        decisionNote = note;
    }

    void reject(UUID actorId, String actorName, String reason) {
        status = RequestStatus.REJECTED;
        decidedBy = actorId;
        decidedByName = actorName;
        decidedAt = Instant.now();
        decisionNote = reason;
    }

    void cancel(UUID actorId, String actorName) {
        status = RequestStatus.CANCELLED;
        closedBy = actorId;
        closedByName = actorName;
        closedAt = Instant.now();
    }

    void closeShort(UUID actorId, String actorName, String reason) {
        status = RequestStatus.CLOSED_SHORT;
        closedBy = actorId;
        closedByName = actorName;
        closedAt = Instant.now();
        closeReason = reason;
    }

    void recordDelivery(BigDecimal quantity) {
        receivedQuantity = receivedQuantity.add(quantity);
        settle();
    }

    /** A delivery's stock receipt was reversed: the quantity no longer counts and the request may reopen. */
    void reverseDelivery(BigDecimal quantity) {
        receivedQuantity = receivedQuantity.subtract(quantity);
        settle();
    }

    void markUpdated() {
        updatedAt = Instant.now();
    }

    int getNumber() {
        return number;
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    BigDecimal getRequestedQuantity() {
        return requestedQuantity;
    }

    BigDecimal getApprovedQuantity() {
        return approvedQuantity;
    }

    BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    RequestStatus getStatus() {
        return status;
    }

    UUID getRequesterId() {
        return requesterId;
    }

    String getRequesterName() {
        return requesterName;
    }

    String getNote() {
        return note;
    }

    String getDecidedByName() {
        return decidedByName;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    String getDecisionNote() {
        return decisionNote;
    }

    String getClosedByName() {
        return closedByName;
    }

    Instant getClosedAt() {
        return closedAt;
    }

    String getCloseReason() {
        return closeReason;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
