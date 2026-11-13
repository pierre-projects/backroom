package com.backroom.restocking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RestockReceiptRepository extends JpaRepository<RestockReceipt, UUID> {
    List<RestockReceipt> findByOrganizationIdAndRequestIdOrderByCreatedAtAscIdAsc(UUID orgId, UUID requestId);

    Optional<RestockReceipt> findByOrganizationIdAndStockOperationId(UUID orgId, UUID stockOperationId);
}
