package com.backroom.inventory;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlacementRepository extends JpaRepository<ItemPlacement, UUID> {
    boolean existsByOrganizationIdAndLocationIdAndActiveTrue(UUID orgId, UUID locationId);
    Optional<ItemPlacement> findByOrganizationIdAndItemIdAndLocationId(UUID orgId, UUID itemId, UUID locationId);
    Page<ItemPlacement> findByOrganizationIdAndItemIdAndActiveTrue(UUID orgId, UUID itemId, Pageable pageable);
    List<ItemPlacement> findByOrganizationIdAndItemIdAndActiveTrue(UUID orgId, UUID itemId);
}
