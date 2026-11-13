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
    UUID importKey;

    @Column(nullable = false, length = 64)
    String fileSha256;

    @Column(length = 200)
    String fileName;

    @Column(nullable = false)
    UUID createdBy;

    @Column(name = "row_count", nullable = false)
    int rows;

    @Column(nullable = false)
    int itemsCreated;

    @Column(nullable = false)
    int locationsCreated;

    @Column(nullable = false)
    int placements;

    @Column(nullable = false)
    int quantities;

    @Column(nullable = false)
    int minimums;

    @Column(nullable = false)
    int skipped;

    @Column(nullable = false)
    int errors;

    protected ImportBatch() {}

    ImportBatch(UUID organizationId, UUID importKey) {
        this.organizationId = organizationId;
        this.importKey = importKey;
    }
}
