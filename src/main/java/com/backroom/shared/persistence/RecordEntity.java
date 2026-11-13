package com.backroom.shared.persistence;

import com.backroom.shared.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class RecordEntity {
    @Id
    protected UUID id = UUID.randomUUID();

    @Version
    protected Long version;

    @Column(nullable = false, updatable = false)
    protected Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void checkVersion(Long expected) {
        if (expected == null || !expected.equals(version)) {
            throw new ConflictException("STALE_VERSION", "This record changed. Reload it before saving.");
        }
    }
}
