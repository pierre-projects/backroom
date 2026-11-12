package com.backroom.audit;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
interface AuditRepository extends JpaRepository<AuditEvent, UUID> {
    Page<AuditEvent> findByOrganizationId(UUID orgId, Pageable pageable);
}
