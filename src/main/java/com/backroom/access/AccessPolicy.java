package com.backroom.access;

import com.backroom.shared.error.ForbiddenException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.error.UnauthenticatedException;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AccessPolicy {
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final EntityManager entityManager;

    AccessPolicy(MembershipRepository memberships, OrganizationRepository organizations, EntityManager entityManager) {
        this.memberships = memberships;
        this.organizations = organizations;
        this.entityManager = entityManager;
    }

    public AccountPrincipal actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new UnauthenticatedException();
        }
        return principal;
    }

    public Role require(UUID orgId, Role... allowed) {
        var membership = memberships
                .findByOrganizationIdAndUserId(orgId, actor().userId())
                .filter(member -> member.isActive())
                .orElseThrow(NotFoundException::new);
        checkRole(membership, allowed);
        return membership.getRole();
    }
    /** True when the signed-in user is an active member; never throws for non-members. */
    public boolean isActiveMember(UUID orgId) {
        return memberships.existsByOrganizationIdAndUserIdAndActiveTrue(orgId, actor().userId());
    }

    public void lock(UUID orgId, Role... allowed) {
        if (!memberships.existsByOrganizationIdAndUserIdAndActiveTrue(orgId, actor().userId())) {
            throw new NotFoundException();
        }
        lockOrganization(orgId);
        var membership = memberships
                .findByOrganizationIdAndUserId(orgId, actor().userId())
                .orElseThrow(NotFoundException::new);
        entityManager.refresh(membership);
        if (!membership.isActive()) throw new NotFoundException();
        checkRole(membership, allowed);
    }

    void lockOrganization(UUID orgId) {
        organizations.lockById(orgId).orElseThrow(NotFoundException::new);
    }

    private void checkRole(Membership membership, Role[] allowed) {
        if (allowed.length != 0 && Arrays.stream(allowed).noneMatch(role -> role == membership.getRole())) {
            throw new ForbiddenException();
        }
    }
}
