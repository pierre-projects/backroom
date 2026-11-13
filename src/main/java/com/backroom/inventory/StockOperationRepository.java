package com.backroom.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface StockOperationRepository extends JpaRepository<StockOperation, UUID> {
    Optional<StockOperation> findByOrganizationIdAndOperationKey(UUID orgId, UUID operationKey);

    Optional<StockOperation> findByOrganizationIdAndId(UUID orgId, UUID id);

    List<StockOperation> findByOrganizationIdAndIdIn(UUID orgId, Collection<UUID> ids);

    boolean existsByOrganizationIdAndReversesOperationId(UUID orgId, UUID operationId);

    @Query(
            "select o.reversesOperationId from StockOperation o where o.organizationId = :orgId and o.reversesOperationId in :ids")
    Set<UUID> reversedAmong(UUID orgId, Collection<UUID> ids);
}
