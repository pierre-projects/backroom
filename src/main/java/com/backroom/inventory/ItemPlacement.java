package com.backroom.inventory;
import com.backroom.shared.TenantRecord;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="item_placements")
class ItemPlacement extends TenantRecord {
    @Column(nullable=false) UUID itemId;
    @Column(nullable=false) UUID locationId;
    @Column(nullable=false) boolean active=true;
    protected ItemPlacement() {}
    ItemPlacement(UUID orgId, UUID itemId, UUID locationId) {
        this.organizationId=orgId; this.itemId=itemId; this.locationId=locationId;
    }
}
