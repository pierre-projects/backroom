package com.backroom.labels;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ResourceCodeRepository extends JpaRepository<ResourceCode, UUID> {
    Optional<ResourceCode> findByOrganizationIdAndId(UUID orgId, UUID id);
    /** Generated tokens are global, so this lookup is deliberately not organization-scoped. Callers check membership. */
    Optional<ResourceCode> findByKindAndValue(ResourceCode.Kind kind, String value);

    List<ResourceCode> findByOrganizationIdAndKindAndValueInAndStatus(
            UUID orgId, ResourceCode.Kind kind, Collection<String> values, ResourceCode.Status status);

    List<ResourceCode> findByOrganizationIdAndItemIdOrderByCreatedAtAscIdAsc(UUID orgId, UUID itemId);

    List<ResourceCode> findByOrganizationIdAndLocationIdOrderByCreatedAtAscIdAsc(UUID orgId, UUID locationId);

    @Query("""
            select c from ResourceCode c where c.organizationId = :orgId and c.kind = com.backroom.labels.ResourceCode.Kind.GENERATED
              and c.status = com.backroom.labels.ResourceCode.Status.ACTIVE
              and ((:itemId is not null and c.itemId = :itemId) or (:locationId is not null and c.locationId = :locationId))
            """)
    Optional<ResourceCode> findActiveQr(UUID orgId, UUID itemId, UUID locationId);
}
