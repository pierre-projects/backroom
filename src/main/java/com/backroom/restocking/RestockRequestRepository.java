package com.backroom.restocking;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RestockRequestRepository extends JpaRepository<RestockRequest, UUID> {
    Optional<RestockRequest> findByOrganizationIdAndId(UUID orgId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RestockRequest r where r.organizationId = :orgId and r.id = :id")
    Optional<RestockRequest> lockById(UUID orgId, UUID id);

    @Query("select coalesce(max(r.number), 0) from RestockRequest r where r.organizationId = :orgId")
    int maxNumber(UUID orgId);

    @Query("""
            select r from RestockRequest r where r.organizationId = :orgId and r.status in :statuses
              and (:requesterId is null or r.requesterId = :requesterId)
              and (:itemId is null or r.itemId = :itemId)
              and (:locationId is null or r.locationId = :locationId)
            """)
    Page<RestockRequest> search(
            UUID orgId,
            Collection<RequestStatus> statuses,
            UUID requesterId,
            UUID itemId,
            UUID locationId,
            Pageable pageable);

    long countByOrganizationIdAndStatusIn(UUID orgId, Collection<RequestStatus> statuses);
}
