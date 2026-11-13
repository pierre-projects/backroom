package com.backroom.imports;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
    Optional<ImportBatch> findByOrganizationIdAndImportKey(UUID organizationId, UUID importKey);
}
