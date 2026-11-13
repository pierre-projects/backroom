package com.backroom.inventory;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "items")
class Item extends TenantRecord {
    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 80)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Unit unit;

    @Column(nullable = false)
    private boolean archived;

    protected Item() {}

    Item(UUID orgId, String name, String sku, Unit unit) {
        this.organizationId = orgId;
        this.name = name;
        this.sku = sku;
        this.unit = unit;
    }

    void edit(String newName, String newSku, Unit newUnit) {
        name = newName;
        sku = newSku;
        unit = newUnit;
    }

    void archive() {
        archived = true;
    }

    String getName() {
        return name;
    }

    String getSku() {
        return sku;
    }

    Unit getUnit() {
        return unit;
    }

    boolean isArchived() {
        return archived;
    }
}
