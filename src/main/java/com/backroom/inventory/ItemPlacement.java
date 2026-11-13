package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "item_placements")
class ItemPlacement extends TenantRecord {
    @Column(nullable = false)
    UUID itemId;

    @Column(nullable = false)
    UUID locationId;

    @Column(nullable = false)
    boolean active = true;
    /** Optional: stock below this at this location counts as low. */
    @Column(precision = 15, scale = 3)
    java.math.BigDecimal minimumQuantity;

    protected ItemPlacement() {}

    ItemPlacement(UUID orgId, UUID itemId, UUID locationId) {
        this.organizationId = orgId;
        this.itemId = itemId;
        this.locationId = locationId;
    }
}
