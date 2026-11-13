package com.backroom.access;

import com.backroom.shared.persistence.RecordEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_resets")
class PasswordReset extends RecordEntity {
    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private UUID issuedBy;

    @Column(nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    private Instant usedAt;
    private Instant revokedAt;

    protected PasswordReset() {}

    PasswordReset(
            UUID userId, UUID organizationId, UUID issuedBy, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.issuedBy = issuedBy;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    boolean open(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    void revoke(Instant at) {
        revokedAt = at;
    }

    void markUsed(Instant at) {
        usedAt = at;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
