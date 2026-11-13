package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An item's photo, stored as JPEG bytes. Read lists through {@link ItemPhotoRepository#versions} so bytes are not loaded. */
@Entity
@Table(name = "item_photos")
class ItemPhoto extends TenantRecord {
    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private byte[] bytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private UUID updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ItemPhoto() {}

    ItemPhoto(UUID organizationId, UUID itemId) {
        this.organizationId = organizationId;
        this.itemId = itemId;
    }
    /** Changes whenever the photo changes, including after remove and re-add; used to version image URLs. */
    long photoVersion() {
        return updatedAt.toEpochMilli();
    }

    /** Replaces the image. {@code updatedAt} strictly increases, so a replaced photo always gets a new URL. */
    void replace(String newContentType, byte[] newBytes, int newWidth, int newHeight, UUID by, Instant now) {
        contentType = newContentType;
        bytes = newBytes;
        width = newWidth;
        height = newHeight;
        updatedBy = by;
        updatedAt = updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plusMillis(1) : now;
    }

    String getContentType() {
        return contentType;
    }

    byte[] getBytes() {
        return bytes;
    }
}
