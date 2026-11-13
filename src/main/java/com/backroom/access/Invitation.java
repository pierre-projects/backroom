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
    private Role role;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;
    private Instant acceptedAt;
    private UUID acceptedBy;

    protected Invitation() {}

    Invitation(UUID organizationId, Role role, String hash, Instant expiresAt) {
        this.organizationId = organizationId;
        this.role = role;
        this.tokenHash = hash;
        this.expiresAt = expiresAt;
    }

    void revoke(Instant at) {
        revokedAt = at;
    }

    void accept(UUID userId, Instant at) {
        acceptedAt = at;
        acceptedBy = userId;
    }

    Role getRole() {
        return role;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    Instant getAcceptedAt() {
        return acceptedAt;
    }
}
