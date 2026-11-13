package com.backroom.access;

import com.backroom.shared.ApiException;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        return principal;
    }

    public Role require(UUID orgId, Role... allowed) {
        var membership = memberships
                .findByOrganizationIdAndUserId(orgId, actor().userId())
                .filter(member -> member.active)
                .orElseThrow(ApiException::notFound);
        checkRole(membership, allowed);
        return membership.role;
    }
    /** True when the signed-in user is an active member; never throws for non-members. */
    public boolean isActiveMember(UUID orgId) {
        return memberships.existsByOrganizationIdAndUserIdAndActiveTrue(orgId, actor().userId());
    }

    public void lock(UUID orgId, Role... allowed) {
        if (!memberships.existsByOrganizationIdAndUserIdAndActiveTrue(orgId, actor().userId())) {
            throw ApiException.notFound();
        }
        lockOrganization(orgId);
        var membership = memberships
                .findByOrganizationIdAndUserId(orgId, actor().userId())
                .orElseThrow(ApiException::notFound);
        entityManager.refresh(membership);
        if (!membership.active) throw ApiException.notFound();
        checkRole(membership, allowed);
    }

    void lockOrganization(UUID orgId) {
        organizations.lockById(orgId).orElseThrow(ApiException::notFound);
    }

    private void checkRole(Membership membership, Role[] allowed) {
        if (allowed.length != 0 && Arrays.stream(allowed).noneMatch(role -> role == membership.role)) {
            throw ApiException.forbidden();
        }
    }
}
