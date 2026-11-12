package com.backroom.access;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String hash);
    Optional<Invitation> findByOrganizationIdAndId(UUID organizationId, UUID id);
    Page<Invitation> findByOrganizationId(UUID organizationId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.organizationId = :orgId and i.id = :id")
    Optional<Invitation> lockByOrganizationAndId(UUID orgId, UUID id);
}
