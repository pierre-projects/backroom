package com.backroom.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserIdAndActiveTrue(UUID userId);

    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserIdAndActiveTrue(UUID organizationId, UUID userId);

    long countByOrganizationIdAndRoleAndActiveTrue(UUID organizationId, Role role);

    Page<Membership> findByOrganizationId(UUID organizationId, Pageable pageable);
}
