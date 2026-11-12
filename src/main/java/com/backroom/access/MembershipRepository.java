package com.backroom.access;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findByUserIdAndActiveTrue(UUID userId);
    Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
    boolean existsByOrganizationIdAndUserIdAndActiveTrue(UUID organizationId, UUID userId);
    long countByOrganizationIdAndRoleAndActiveTrue(UUID organizationId, Role role);
    Page<Membership> findByOrganizationId(UUID organizationId, Pageable pageable);
}
