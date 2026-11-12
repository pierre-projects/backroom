package com.backroom.inventory;
import com.backroom.shared.TenantRecord;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="items")
class Item extends TenantRecord {
    @Column(nullable=false, length=160) String name;
    @Column(length=80) String sku;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) Unit unit;
    @Column(nullable=false) boolean archived;
    protected Item() {}
    Item(UUID orgId, String name, String sku, Unit unit) {
        this.organizationId=orgId; this.name=name; this.sku=sku; this.unit=unit;
    }
}
