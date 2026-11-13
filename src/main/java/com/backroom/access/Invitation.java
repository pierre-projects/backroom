package com.backroom.access;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
class Invitation extends TenantRecord {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    Role role;

    @Column(nullable = false, length = 64)
    String tokenHash;

    @Column(nullable = false)
    Instant expiresAt;

    Instant revokedAt;
    Instant acceptedAt;
    UUID acceptedBy;

    protected Invitation() {}

    Invitation(UUID organizationId, Role role, String hash, Instant expiresAt) {
        this.organizationId = organizationId;
        this.role = role;
        this.tokenHash = hash;
        this.expiresAt = expiresAt;
    }
}
