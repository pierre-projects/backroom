package com.backroom.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ItemPhotoRepository extends JpaRepository<ItemPhoto, UUID> {
    Optional<ItemPhoto> findByOrganizationIdAndItemId(UUID organizationId, UUID itemId);

    @Query("select p.itemId, p.updatedAt from ItemPhoto p where p.organizationId = :orgId and p.itemId in :itemIds")
    List<Object[]> versions(UUID orgId, Collection<UUID> itemIds);
}
