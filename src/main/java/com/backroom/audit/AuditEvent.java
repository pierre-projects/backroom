package com.backroom.audit;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.*;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
class AuditEvent extends TenantRecord {
    @Column(nullable = false) UUID actorId;
    @Column(nullable = false, length = 100) String actorName;
    @Column(nullable = false, length = 80) String action;
    @Column(nullable = false, length = 30) String resourceType;
    @Column(nullable = false) UUID resourceId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    Map<String, String> metadata;
    protected AuditEvent() {}
    AuditEvent(UUID orgId, UUID actorId, String actorName, String action, String resourceType, UUID resourceId,
               Map<String, String> metadata, java.time.Instant now) {
        this.organizationId = orgId; this.actorId = actorId; this.actorName = actorName;
        this.action = action; this.resourceType = resourceType; this.resourceId = resourceId;
        this.metadata = metadata; this.createdAt = now;
    }
}
