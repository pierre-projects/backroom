package com.backroom.access;

import com.backroom.shared.RecordEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

@Entity
@Table(name = "password_resets")
class PasswordReset extends RecordEntity {
    @Column(nullable = false, updatable = false) UUID userId;
    @Column(nullable = false, updatable = false) UUID organizationId;
    @Column(nullable = false, updatable = false) UUID issuedBy;
    @Column(nullable = false, updatable = false, length = 64) String tokenHash;
    @Column(nullable = false, updatable = false) Instant expiresAt;
    Instant usedAt;
    Instant revokedAt;
    protected PasswordReset() {}
    PasswordReset(UUID userId, UUID organizationId, UUID issuedBy, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId; this.organizationId = organizationId; this.issuedBy = issuedBy;
        this.tokenHash = tokenHash; this.createdAt = createdAt; this.expiresAt = expiresAt;
    }
    boolean open(Instant now) { return usedAt == null && revokedAt == null && expiresAt.isAfter(now); }
}

interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordReset r where r.tokenHash = :tokenHash")
    Optional<PasswordReset> lockByTokenHash(String tokenHash);
    List<PasswordReset> findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(UUID userId);
}
