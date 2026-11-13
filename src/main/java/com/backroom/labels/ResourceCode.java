package com.backroom.labels;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A permanent identifier for one item or location. Only its revocation can change after it is created. */
@Entity
@Table(name = "resource_codes")
class ResourceCode extends TenantRecord {
    enum Kind {
        GENERATED,
        MANUFACTURER
    }

    enum Status {
        ACTIVE,
        REVOKED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, updatable = false, length = 20)
    private String value;

    @Column(updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    private Instant revokedAt;
    private UUID revokedBy;

    protected ResourceCode() {}

    ResourceCode(UUID orgId, Kind kind, String value, UUID itemId, UUID locationId, UUID createdBy) {
        this.organizationId = orgId;
        this.kind = kind;
        this.value = value;
        this.itemId = itemId;
        this.locationId = locationId;
        this.createdBy = createdBy;
    }

    boolean active() {
        return status == Status.ACTIVE;
    }

    void revoke(UUID by) {
        status = Status.REVOKED;
        revokedAt = Instant.now();
        revokedBy = by;
    }

    Kind getKind() {
        return kind;
    }

    String getValue() {
        return value;
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    Status getStatus() {
        return status;
    }
}
