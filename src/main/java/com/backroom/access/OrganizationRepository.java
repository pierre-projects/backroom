package com.backroom.access;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> lockById(UUID id);
    @Query("select o from Organization o where exists (select m from Membership m where m.organizationId = o.id and m.userId = :userId and m.active = true)")
    Page<Organization> listForUser(UUID userId, Pageable pageable);
}
