package com.backroom.access;

import static com.backroom.access.Role.EMPLOYEE;
import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;

import com.backroom.audit.AuditService;
import com.backroom.shared.web.ApiException;
import com.backroom.shared.web.PageResponse;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InvitationService {
    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final AccessPolicy access;
    private final AuditService audit;
    private final Clock clock;
    private final EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    InvitationService(
            InvitationRepository invitations,
            MembershipRepository memberships,
            OrganizationRepository organizations,
            AccessPolicy access,
            AuditService audit,
            Clock clock,
            EntityManager entityManager) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.organizations = organizations;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    public AccessDtos.CreatedInvitation create(UUID orgId, Role role) {
        access.lock(orgId, OWNER);
        if (role != MANAGER && role != EMPLOYEE)
            throw ApiException.badRequest("Invite a Manager or Employee. Promote Owners through member management.");
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var invite = invitations.saveAndFlush(
                new Invitation(orgId, role, digest(token), clock.instant().plus(Duration.ofDays(7))));
        record(invite, "INVITATION_CREATED", Map.of("role", role.name()));
        return new AccessDtos.CreatedInvitation(view(invite), "/invite#token=" + token);
    }

    @Transactional(readOnly = true)
    public PageResponse<AccessDtos.InvitationView> list(UUID orgId, int page, int size) {
        access.require(orgId, OWNER);
        return PageResponse.from(invitations
                .findByOrganizationId(
                        orgId, PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(this::view));
    }

    public void revoke(UUID orgId, UUID invitationId) {
        access.lock(orgId, OWNER);
        var invite = invitations.lockByOrganizationAndId(orgId, invitationId).orElseThrow(ApiException::notFound);
        entityManager.refresh(invite);
        if (invite.revokedAt != null) return;
        ensurePending(invite);
        invite.revokedAt = clock.instant();
        invitations.flush();
        record(invite, "INVITATION_REVOKED", Map.of());
    }

    @Transactional(readOnly = true)
    public AccessDtos.InvitationPreview preview(String token) {
        access.actor();
        var invite = invitations.findByTokenHash(digest(token)).orElseThrow(ApiException::notFound);
        ensurePending(invite);
        var org = organizations.findById(invite.getOrganizationId()).orElseThrow(ApiException::notFound);
        return new AccessDtos.InvitationPreview(org.getId(), org.name, invite.role, invite.expiresAt);
    }

    public AccessDtos.OrganizationView accept(String token) {
        var actor = access.actor();
        var reference = invitations.findByTokenHash(digest(token)).orElseThrow(ApiException::notFound);
        var orgId = reference.getOrganizationId();
        access.lockOrganization(orgId);
        var invite =
                invitations.lockByOrganizationAndId(orgId, reference.getId()).orElseThrow(ApiException::notFound);
        entityManager.refresh(invite);
        ensurePending(invite);
        var member =
                memberships.findByOrganizationIdAndUserId(orgId, actor.userId()).orElse(null);
        if (member != null && member.active) {
            throw ApiException.conflict(
                    "ALREADY_MEMBER", "You already belong to this organization. Your role has not changed.");
        }
        if (member == null) member = new Membership(orgId, actor.userId(), invite.role);
        member.active = true;
        member.role = invite.role;
        memberships.saveAndFlush(member);
        invite.acceptedAt = clock.instant();
        invite.acceptedBy = actor.userId();
        invitations.flush();
        record(invite, "INVITATION_ACCEPTED", Map.of("role", invite.role.name()));
        var org = organizations.findById(orgId).orElseThrow(ApiException::notFound);
        return new AccessDtos.OrganizationView(orgId, org.name, org.getVersion(), member.role);
    }

    private void ensurePending(Invitation invite) {
        if (!"PENDING".equals(status(invite))) {
            throw ApiException.conflict(
                    "INVITATION_UNAVAILABLE", "This invitation has expired, been revoked, or already been used.");
        }
    }

    private String status(Invitation invite) {
        if (invite.acceptedAt != null) return "ACCEPTED";
        if (invite.revokedAt != null) return "REVOKED";
        return invite.expiresAt.isAfter(clock.instant()) ? "PENDING" : "EXPIRED";
    }

    private AccessDtos.InvitationView view(Invitation invite) {
        return new AccessDtos.InvitationView(
                invite.getId(), invite.role, invite.expiresAt, status(invite), invite.acceptedAt);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private void record(Invitation invite, String action, Map<String, String> metadata) {
        var actor = access.actor();
        audit.record(
                invite.getOrganizationId(),
                actor.userId(),
                actor.displayName(),
                action,
                "INVITATION",
                invite.getId(),
                metadata);
    }
}
