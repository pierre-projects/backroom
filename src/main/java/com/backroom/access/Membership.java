package com.backroom.access;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "memberships")
class Membership extends TenantRecord {
    @Column(nullable = false)
    UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    Role role;

    @Column(nullable = false)
    boolean active = true;

    protected Membership() {}

    Membership(UUID organizationId, UUID userId, Role role) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
    }
}
