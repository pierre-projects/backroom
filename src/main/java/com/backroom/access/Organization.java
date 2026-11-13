package com.backroom.access;

import com.backroom.shared.persistence.RecordEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
class Organization extends RecordEntity {
    @Column(nullable = false, length = 120)
    private String name;

    protected Organization() {}

    Organization(String name) {
        this.name = name;
    }

    void rename(String newName) {
        name = newName;
    }

    String getName() {
        return name;
    }
}
