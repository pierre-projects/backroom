package com.backroom.shared;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantRecord extends RecordEntity {
    @Column(nullable = false, updatable = false)
    protected UUID organizationId;

    public UUID getOrganizationId() {
        return organizationId;
    }
}
