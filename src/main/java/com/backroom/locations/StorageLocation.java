package com.backroom.locations;

import com.backroom.shared.persistence.TenantRecord;
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
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LocationType type;

    private UUID parentId;

    @Column(nullable = false)
    private boolean archived;

    protected StorageLocation() {}

    StorageLocation(UUID orgId, String name, LocationType type, UUID parentId) {
        this.organizationId = orgId;
        this.name = name;
        this.type = type;
        this.parentId = parentId;
    }

    void edit(String newName, LocationType newType, UUID newParentId) {
        name = newName;
        type = newType;
        parentId = newParentId;
    }

    void archive() {
        archived = true;
    }

    String getName() {
        return name;
    }

    LocationType getType() {
        return type;
    }

    UUID getParentId() {
        return parentId;
    }

    boolean isArchived() {
        return archived;
    }
}
