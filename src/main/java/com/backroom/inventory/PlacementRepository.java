package com.backroom.inventory;
import java.util.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface PlacementRepository extends JpaRepository<ItemPlacement, UUID> {
    @Query("""
            select p.locationId, count(p) from ItemPlacement p, Item i
            where p.organizationId = :orgId and i.organizationId = :orgId and i.id = p.itemId
              and p.active = true and i.archived = false and p.locationId in :locationIds
            group by p.locationId
            """)
    List<Object[]> itemCounts(UUID orgId, Collection<UUID> locationIds);
    /** Creates an active placement if the slot has none. Existing (possibly inactive) rows are left for the caller to lock. */
    @Modifying(flushAutomatically=true)
    @Query(nativeQuery=true, value="""
            insert into item_placements (id, version, created_at, organization_id, item_id, location_id, active)
            values (gen_random_uuid(), 0, now(), :orgId, :itemId, :locationId, true)
            on conflict (organization_id, item_id, location_id) do nothing
            """)
    int insertIfMissing(UUID orgId, UUID itemId, UUID locationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ItemPlacement p where p.organizationId = :orgId and p.itemId = :itemId and p.locationId = :locationId")
    Optional<ItemPlacement> lockSlot(UUID orgId, UUID itemId, UUID locationId);
    @Query("select p.locationId from ItemPlacement p where p.organizationId = :orgId and p.itemId = :itemId and p.active = true")
    Set<UUID> activeLocations(UUID orgId, UUID itemId);
    boolean existsByOrganizationIdAndLocationIdAndActiveTrue(UUID orgId, UUID locationId);
    Optional<ItemPlacement> findByOrganizationIdAndItemIdAndLocationId(UUID orgId, UUID itemId, UUID locationId);
    Page<ItemPlacement> findByOrganizationIdAndItemIdAndActiveTrue(UUID orgId, UUID itemId, Pageable pageable);
    List<ItemPlacement> findByOrganizationIdAndItemIdAndActiveTrue(UUID orgId, UUID itemId);
}
