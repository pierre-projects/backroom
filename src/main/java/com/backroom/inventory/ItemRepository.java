package com.backroom.inventory;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

interface ItemRepository extends JpaRepository<Item, UUID> {
    @Query("select i from Item i where i.organizationId = :orgId and i.archived = false and exists (select p from ItemPlacement p where p.organizationId = :orgId and p.itemId = i.id and p.locationId = :locationId and p.active = true)")
    Page<Item> atLocation(UUID orgId, UUID locationId, Pageable pageable);
    Optional<Item> findByOrganizationIdAndId(UUID orgId, UUID id);
    boolean existsByOrganizationIdAndSkuAndIdNot(UUID orgId, String sku, UUID id);
    @Query("""
            select i from Item i where i.organizationId = :orgId
            and (:includeArchived = true or i.archived = false)
            and (:q = '' or lower(i.name) like concat('%', lower(:q), '%') or lower(i.sku) like concat('%', lower(:q), '%'))
            """)
    Page<Item> search(UUID orgId, boolean includeArchived, String q, Pageable pageable);
}
