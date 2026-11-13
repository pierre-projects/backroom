package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "item_placements")
class ItemPlacement extends TenantRecord {
    @Column(nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private UUID locationId;

    @Column(nullable = false)
    private boolean active = true;
    /** Optional: stock below this at this location counts as low. */
    @Column(precision = 15, scale = 3)
    private BigDecimal minimumQuantity;

    protected ItemPlacement() {}

    ItemPlacement(UUID orgId, UUID itemId, UUID locationId) {
        this.organizationId = orgId;
        this.itemId = itemId;
        this.locationId = locationId;
    }

    void activate() {
        active = true;
    }

    /** Stops stocking the item here; a removed placement keeps no minimum. */
    void deactivate() {
        active = false;
        minimumQuantity = null;
    }

    void changeMinimum(BigDecimal minimum) {
        minimumQuantity = minimum;
    }

    UUID getItemId() {
        return itemId;
    }

    UUID getLocationId() {
        return locationId;
    }

    boolean isActive() {
        return active;
    }

    BigDecimal getMinimumQuantity() {
        return minimumQuantity;
    }
}
