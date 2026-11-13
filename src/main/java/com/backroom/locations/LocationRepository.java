package com.backroom.locations;
import java.util.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface LocationRepository extends JpaRepository<StorageLocation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select l from StorageLocation l where l.organizationId = :orgId and l.id = :id")
    Optional<StorageLocation> lockForShare(UUID orgId, UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StorageLocation l where l.organizationId = :orgId and l.id = :id")
    Optional<StorageLocation> lockForUpdate(UUID orgId, UUID id);
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

    /** The chain of parents, outermost first. Every step stays inside the organization; depth is capped as a safeguard. */
    @Query(nativeQuery=true, value="""
            with recursive up(id, name, type, parent_id, depth) as (
                select l.id, l.name, l.type, l.parent_id, 0 from locations l where l.organization_id = :orgId and l.id = :id
                union all
                select p.id, p.name, p.type, p.parent_id, up.depth + 1 from locations p join up on p.id = up.parent_id
                where p.organization_id = :orgId and up.depth < 32
            )
            select cast(id as varchar) as id, name, type from up where depth > 0 order by depth desc
            """)
    List<Object[]> ancestors(UUID orgId, UUID id);

    @Query("select l.parentId, count(l) from StorageLocation l where l.organizationId = :orgId and l.parentId in :ids and l.archived = false group by l.parentId")
    List<Object[]> childCounts(UUID orgId, Collection<UUID> ids);
}
