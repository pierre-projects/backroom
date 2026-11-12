package com.backroom.restocking;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Persistence records for restocking. */
final class RestockRecords {
    private RestockRecords() {}
}

@Entity
@Table(name="restock_requests")
class RestockRequest extends TenantRecord {
    @Column(nullable=false, updatable=false) int number;
    @Column(nullable=false, updatable=false) UUID itemId;
    @Column(nullable=false, updatable=false) UUID locationId;
    @Column(nullable=false, updatable=false, precision=15, scale=3) BigDecimal requestedQuantity;
    @Column(precision=15, scale=3) BigDecimal approvedQuantity;
    @Column(nullable=false, precision=15, scale=3) BigDecimal receivedQuantity = BigDecimal.ZERO.setScale(3);
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) RequestStatus status = RequestStatus.SUBMITTED;
    @Column(nullable=false, updatable=false) UUID requesterId;
    @Column(nullable=false, updatable=false, length=100) String requesterName;
    @Column(updatable=false, length=500) String note;
    UUID decidedBy;
    @Column(length=100) String decidedByName;
    Instant decidedAt;
    @Column(length=500) String decisionNote;
    UUID closedBy;
    @Column(length=100) String closedByName;
    Instant closedAt;
    @Column(length=500) String closeReason;
    @Column(nullable=false) Instant updatedAt = Instant.now();
    protected RestockRequest() {}
    RestockRequest(UUID orgId, int number, UUID itemId, UUID locationId, BigDecimal quantity, UUID requesterId, String requesterName, String note) {
        this.organizationId=orgId; this.number=number; this.itemId=itemId; this.locationId=locationId; this.requestedQuantity=quantity;
        this.requesterId=requesterId; this.requesterName=requesterName; this.note=note;
    }
    BigDecimal remaining() { return approvedQuantity == null ? BigDecimal.ZERO : approvedQuantity.subtract(receivedQuantity); }
    /** Status after the received quantity changed, for requests that are still open for deliveries. */
    void settle() {
        if (status == RequestStatus.CLOSED_SHORT) return;
        status = receivedQuantity.signum() == 0 ? RequestStatus.APPROVED
                : receivedQuantity.compareTo(approvedQuantity) >= 0 ? RequestStatus.FULFILLED : RequestStatus.PARTIALLY_RECEIVED;
    }
}

@Entity
@Immutable
@Table(name="restock_receipts")
class RestockReceipt extends TenantRecord {
    @Column(nullable=false) UUID requestId;
    @Column(nullable=false) UUID stockOperationId;
    @Column(nullable=false, precision=15, scale=3) BigDecimal quantity;
    @Column(nullable=false) UUID receiverId;
    @Column(nullable=false, length=100) String receiverName;
    protected RestockReceipt() {}
    RestockReceipt(UUID orgId, UUID requestId, UUID stockOperationId, BigDecimal quantity, UUID receiverId, String receiverName) {
        this.organizationId=orgId; this.requestId=requestId; this.stockOperationId=stockOperationId; this.quantity=quantity;
        this.receiverId=receiverId; this.receiverName=receiverName;
    }
}

interface RestockRequestRepository extends JpaRepository<RestockRequest, UUID> {
    Optional<RestockRequest> findByOrganizationIdAndId(UUID orgId, UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RestockRequest r where r.organizationId = :orgId and r.id = :id")
    Optional<RestockRequest> lockById(UUID orgId, UUID id);
    @Query("select coalesce(max(r.number), 0) from RestockRequest r where r.organizationId = :orgId")
    int maxNumber(UUID orgId);
    @Query("""
            select r from RestockRequest r where r.organizationId = :orgId and r.status in :statuses
              and (:requesterId is null or r.requesterId = :requesterId)
              and (:itemId is null or r.itemId = :itemId)
              and (:locationId is null or r.locationId = :locationId)
            """)
    Page<RestockRequest> search(UUID orgId, Collection<RequestStatus> statuses, UUID requesterId, UUID itemId, UUID locationId, Pageable pageable);
    long countByOrganizationIdAndStatusIn(UUID orgId, Collection<RequestStatus> statuses);
}

interface RestockReceiptRepository extends JpaRepository<RestockReceipt, UUID> {
    List<RestockReceipt> findByOrganizationIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID orgId, UUID requestId);
    Optional<RestockReceipt> findByOrganizationIdAndStockOperationId(UUID orgId, UUID stockOperationId);
}
