package com.backroom.inventory;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

/** Repositories for stock records. Only the inventory module uses them. */
final class StockRepositories {
    private StockRepositories() {}
}

interface StockBalanceRepository extends JpaRepository<StockBalance, UUID> {
    /** Creates an empty balance for a placement if none exists. Safe under concurrency through the slot constraint. */
    @Modifying(flushAutomatically=true)
    @Query(nativeQuery=true, value="""
            insert into stock_balances (id, version, created_at, organization_id, item_id, location_id, quantity, updated_at)
            values (gen_random_uuid(), 0, now(), :orgId, :itemId, :locationId, 0, now())
            on conflict on constraint stock_balances_slot_uq do nothing
            """)
    int insertIfMissing(UUID orgId, UUID itemId, UUID locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from StockBalance b where b.organizationId = :orgId and b.itemId = :itemId and b.locationId = :locationId")
    Optional<StockBalance> lockSlot(UUID orgId, UUID itemId, UUID locationId);

    List<StockBalance> findByOrganizationIdAndItemId(UUID orgId, UUID itemId);
    List<StockBalance> findByOrganizationIdAndLocationIdAndItemIdIn(UUID orgId, UUID locationId, Collection<UUID> itemIds);
    boolean existsByOrganizationIdAndItemIdAndQuantityGreaterThan(UUID orgId, UUID itemId, BigDecimal quantity);
    boolean existsByOrganizationIdAndLocationIdAndQuantityGreaterThan(UUID orgId, UUID locationId, BigDecimal quantity);

    @Query("select b.itemId, sum(b.quantity) from StockBalance b where b.organizationId = :orgId and b.itemId in :itemIds group by b.itemId")
    List<Object[]> totals(UUID orgId, Collection<UUID> itemIds);

    /** Active slots of unarchived items whose stock is below their minimum, as [balance, minimum]. */
    @Query(value = """
            select b, p.minimumQuantity from StockBalance b, ItemPlacement p, Item i
            where b.organizationId = :orgId and p.organizationId = :orgId and i.organizationId = :orgId
              and p.itemId = b.itemId and p.locationId = b.locationId and i.id = b.itemId
              and p.active = true and i.archived = false and p.minimumQuantity is not null and b.quantity < p.minimumQuantity
              and (:locationId is null or b.locationId = :locationId)
            order by i.name, b.locationId
            """, countQuery = """
            select count(b) from StockBalance b, ItemPlacement p, Item i
            where b.organizationId = :orgId and p.organizationId = :orgId and i.organizationId = :orgId
              and p.itemId = b.itemId and p.locationId = b.locationId and i.id = b.itemId
              and p.active = true and i.archived = false and p.minimumQuantity is not null and b.quantity < p.minimumQuantity
              and (:locationId is null or b.locationId = :locationId)
            """)
    Page<Object[]> lowStock(UUID orgId, UUID locationId, Pageable pageable);

    @Query("""
            select distinct b.itemId from StockBalance b, ItemPlacement p where b.organizationId = :orgId and p.organizationId = :orgId
              and p.itemId = b.itemId and p.locationId = b.locationId and p.active = true and p.minimumQuantity is not null
              and b.quantity < p.minimumQuantity and b.itemId in :itemIds
            """)
    Set<UUID> lowAmong(UUID orgId, Collection<UUID> itemIds);
}

interface StockOperationRepository extends JpaRepository<StockOperation, UUID> {
    Optional<StockOperation> findByOrganizationIdAndOperationKey(UUID orgId, UUID operationKey);
    Optional<StockOperation> findByOrganizationIdAndId(UUID orgId, UUID id);
    List<StockOperation> findByOrganizationIdAndIdIn(UUID orgId, Collection<UUID> ids);
    boolean existsByOrganizationIdAndReversesOperationId(UUID orgId, UUID operationId);
    @Query("select o.reversesOperationId from StockOperation o where o.organizationId = :orgId and o.reversesOperationId in :ids")
    Set<UUID> reversedAmong(UUID orgId, Collection<UUID> ids);
}

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

interface StockCountRepository extends JpaRepository<StockCount, UUID> {
    Optional<StockCount> findByOrganizationIdAndOperationId(UUID orgId, UUID operationId);
}
