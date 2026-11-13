package com.backroom.locations;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "locations")
class StorageLocation extends TenantRecord {
    @Column(nullable = false, length = 120)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    LocationType type;

    UUID parentId;

    @Column(nullable = false)
    boolean archived;

    protected StorageLocation() {}

    StorageLocation(UUID orgId, String name, LocationType type, UUID parentId) {
        this.organizationId = orgId;
        this.name = name;
        this.type = type;
        this.parentId = parentId;
    }
}
