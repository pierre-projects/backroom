package com.backroom.locations;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

interface LocationRepository extends JpaRepository<StorageLocation, UUID> {
    Optional<StorageLocation> findByOrganizationIdAndId(UUID orgId, UUID id);
    List<StorageLocation> findByOrganizationIdAndIdIn(UUID orgId, Collection<UUID> ids);
    boolean existsByOrganizationIdAndParentIdAndArchivedFalse(UUID orgId, UUID parentId);
    @Query("""
            select l from StorageLocation l
            where l.organizationId = :orgId
              and (:includeArchived = true or l.archived = false)
              and (:parentId is null or l.parentId = :parentId)
              and (:rootsOnly = false or l.parentId is null)
              and (:leafOnly = false or not exists
                   (select child from StorageLocation child where child.organizationId = :orgId and child.parentId = l.id and child.archived = false))
              and (:q = '' or lower(l.name) like concat('%', lower(:q), '%'))
            """)
    Page<StorageLocation> search(UUID orgId, UUID parentId, boolean rootsOnly, boolean leafOnly,
                                boolean includeArchived, String q, Pageable pageable);
}
