package com.backroom.inventory;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface StockCountRepository extends JpaRepository<StockCount, UUID> {
    Optional<StockCount> findByOrganizationIdAndOperationId(UUID orgId, UUID operationId);
}
