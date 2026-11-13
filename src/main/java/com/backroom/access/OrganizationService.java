package com.backroom.access;

import static com.backroom.access.Role.OWNER;

import com.backroom.audit.AuditService;
import com.backroom.shared.web.ApiException;
import com.backroom.shared.web.PageResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrganizationService {
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AccountRepository accounts;
    private final AccessPolicy access;
    private final AuditService audit;

    OrganizationService(
            OrganizationRepository organizations,
            MembershipRepository memberships,
            AccountRepository accounts,
            AccessPolicy access,
            AuditService audit) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.accounts = accounts;
        this.access = access;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessDtos.OrganizationView> list(int page, int size) {
        var actor = access.actor();
        var roles = new HashMap<UUID, Role>();
        memberships
                .findByUserIdAndActiveTrue(actor.userId())
                .forEach(member -> roles.put(member.getOrganizationId(), member.role));
        return PageResponse.from(organizations
                .listForUser(actor.userId(), PageResponse.named(page, size))
                .map(org -> view(org, roles.get(org.getId()))));
    }

    @Transactional(readOnly = true)
    public AccessDtos.OrganizationView get(UUID orgId) {
        var role = access.require(orgId);
        return view(organizations.findById(orgId).orElseThrow(ApiException::notFound), role);
    }

    public AccessDtos.OrganizationView create(AccessDtos.OrganizationInput input) {
        var actor = access.actor();
        var org = organizations.saveAndFlush(new Organization(input.name().strip()));
        memberships.saveAndFlush(new Membership(org.getId(), actor.userId(), OWNER));
        record(org.getId(), "ORGANIZATION_CREATED", "ORGANIZATION", org.getId(), Map.of("name", org.name));
        return view(org, OWNER);
    }

    public AccessDtos.OrganizationView edit(UUID orgId, AccessDtos.OrganizationEdit input) {
        access.lock(orgId, OWNER);
        var org = organizations.findById(orgId).orElseThrow(ApiException::notFound);
        org.checkVersion(input.expectedVersion());
        org.name = input.name().strip();
        organizations.flush();
        record(orgId, "ORGANIZATION_UPDATED", "ORGANIZATION", orgId, Map.of("name", org.name));
        return view(org, OWNER);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessDtos.MemberView> members(UUID orgId, int page, int size) {
        access.require(orgId, OWNER);
        var result =
                memberships.findByOrganizationId(orgId, PageResponse.request(page, size, Sort.by("createdAt", "id")));
        var userIds = result.stream().map(member -> member.userId).toList();
        var users = new HashMap<UUID, Account>();
        accounts.findAllById(userIds).forEach(account -> users.put(account.getId(), account));
        return PageResponse.from(result.map(member -> memberView(member, users.get(member.userId))));
    }

    public AccessDtos.MemberView changeRole(UUID orgId, UUID userId, Role role) {
        access.lock(orgId, OWNER);
        var member = memberships
                .findByOrganizationIdAndUserId(orgId, userId)
                .filter(value -> value.active)
                .orElseThrow(ApiException::notFound);
        protectOwner(orgId, member, role != OWNER);
        if (member.role != role) {
            member.role = role;
            memberships.flush();
            record(orgId, "MEMBERSHIP_ROLE_CHANGED", "MEMBERSHIP", member.getId(), Map.of("role", role.name()));
        }
        return memberView(member, accounts.findById(userId).orElseThrow(ApiException::notFound));
    }

    public void deactivate(UUID orgId, UUID userId) {
        access.lock(orgId, OWNER);
        var member = memberships.findByOrganizationIdAndUserId(orgId, userId).orElseThrow(ApiException::notFound);
        if (!member.active) return;
        protectOwner(orgId, member, true);
        member.active = false;
        memberships.flush();
        record(orgId, "MEMBERSHIP_DEACTIVATED", "MEMBERSHIP", member.getId(), Map.of("userId", userId.toString()));
    }

    private void protectOwner(UUID orgId, Membership member, boolean losesOwnership) {
        if (member.role == OWNER
                && losesOwnership
                && memberships.countByOrganizationIdAndRoleAndActiveTrue(orgId, OWNER) <= 1) {
            throw ApiException.conflict("LAST_OWNER", "An organization must retain an active Owner.");
        }
    }

    private AccessDtos.MemberView memberView(Membership member, Account account) {
        return new AccessDtos.MemberView(
                member.getId(), member.userId, account.displayName, account.email, member.role, member.active);
    }

    private AccessDtos.OrganizationView view(Organization org, Role role) {
        return new AccessDtos.OrganizationView(org.getId(), org.name, org.getVersion(), role);
    }

    private void record(UUID orgId, String action, String type, UUID id, Map<String, String> metadata) {
        var actor = access.actor();
        audit.record(orgId, actor.userId(), actor.displayName(), action, type, id, metadata);
    }
}
