package com.backroom.inventory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
    List<StockMovement> findByOrganizationIdAndOperationIdOrderByQuantityChangeAsc(UUID orgId, UUID operationId);

    boolean existsByOrganizationIdAndItemId(UUID orgId, UUID itemId);

    boolean existsByOrganizationIdAndItemIdAndLocationId(UUID orgId, UUID itemId, UUID locationId);

    @Query("select distinct m.locationId from StockMovement m where m.organizationId = :orgId and m.itemId = :itemId")
    Set<UUID> locationsWithHistory(UUID orgId, UUID itemId);

    @Query("""
            select m from StockMovement m where m.organizationId = :orgId
              and (:itemId is null or m.itemId = :itemId)
              and (:locationId is null or m.locationId = :locationId)
            """)
    Page<StockMovement> search(UUID orgId, UUID itemId, UUID locationId, Pageable pageable);
}
