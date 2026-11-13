package com.backroom.imports;

import com.backroom.shared.persistence.TenantRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** An applied import and what it did. Its key makes a retried import replay this result instead of running again. */
@Entity
@Table(name = "import_batches")
class ImportBatch extends TenantRecord {
    @Column(nullable = false, updatable = false)
    private UUID importKey;

    @Column(nullable = false, length = 64)
    private String fileSha256;

    @Column(length = 200)
    private String fileName;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(name = "row_count", nullable = false)
    private int rows;

    @Column(nullable = false)
    private int itemsCreated;

    @Column(nullable = false)
    private int locationsCreated;

    @Column(nullable = false)
    private int placements;

    @Column(nullable = false)
    private int quantities;

    @Column(nullable = false)
    private int minimums;

    @Column(nullable = false)
    private int skipped;

    @Column(nullable = false)
    private int errors;

    protected ImportBatch() {}

    ImportBatch(
            UUID organizationId, UUID importKey, String fileSha256, String fileName, UUID createdBy, Counts counts) {
        this.organizationId = organizationId;
        this.importKey = importKey;
        this.fileSha256 = fileSha256;
        this.fileName = fileName;
        this.createdBy = createdBy;
        this.rows = counts.rows();
        this.itemsCreated = counts.itemsCreated();
        this.locationsCreated = counts.locationsCreated();
        this.placements = counts.placements();
        this.quantities = counts.quantities();
        this.minimums = counts.minimums();
        this.errors = counts.errors();
        this.skipped = counts.skipped();
    }

    /** What an applied import did, row by row. */
    record Counts(
            int rows,
            int itemsCreated,
            int locationsCreated,
            int placements,
            int quantities,
            int minimums,
            int errors,
            int skipped) {}

    String getFileSha256() {
        return fileSha256;
    }

    int getRows() {
        return rows;
    }

    int getItemsCreated() {
        return itemsCreated;
    }

    int getLocationsCreated() {
        return locationsCreated;
    }

    int getPlacements() {
        return placements;
    }

    int getQuantities() {
        return quantities;
    }

    int getMinimums() {
        return minimums;
    }

    int getSkipped() {
        return skipped;
    }

    int getErrors() {
        return errors;
    }
}
