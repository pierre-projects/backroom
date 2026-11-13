package com.backroom.inventory;

import com.backroom.shared.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** An item's photo, stored as JPEG bytes. Read lists through {@link ItemPhotoRepository#versions} so bytes are not loaded. */
@Entity
@Table(name = "item_photos")
class ItemPhoto extends TenantRecord {
    @Column(nullable = false, updatable = false) UUID itemId;
    @Column(nullable = false, length = 40) String contentType;
    @Column(nullable = false) byte[] bytes;
    @Column(nullable = false) int width;
    @Column(nullable = false) int height;
    @Column(nullable = false) UUID updatedBy;
    @Column(nullable = false) Instant updatedAt;
    protected ItemPhoto() {}
    ItemPhoto(UUID organizationId, UUID itemId) { this.organizationId = organizationId; this.itemId = itemId; }
    /** Changes whenever the photo changes, including after remove and re-add; used to version image URLs. */
    long photoVersion() { return updatedAt.toEpochMilli(); }
}

interface ItemPhotoRepository extends JpaRepository<ItemPhoto, UUID> {
    Optional<ItemPhoto> findByOrganizationIdAndItemId(UUID organizationId, UUID itemId);
    @Query("select p.itemId, p.updatedAt from ItemPhoto p where p.organizationId = :orgId and p.itemId in :itemIds")
    List<Object[]> versions(UUID orgId, Collection<UUID> itemIds);
}
