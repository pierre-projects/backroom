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
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    protected Membership() {}

    Membership(UUID organizationId, UUID userId, Role role) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.role = role;
    }

    /** Joins, or rejoins after being deactivated, with the given role. */
    void activate(Role newRole) {
        active = true;
        role = newRole;
    }

    void changeRole(Role newRole) {
        role = newRole;
    }

    void deactivate() {
        active = false;
    }

    UUID getUserId() {
        return userId;
    }

    Role getRole() {
        return role;
    }

    boolean isActive() {
        return active;
    }
}
