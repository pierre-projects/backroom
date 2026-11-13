package com.backroom.access;

import com.backroom.audit.AuditService;
import com.backroom.shared.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {
    private final OrganizationService organizations;
    private final InvitationService invitations;
    private final AccessPolicy access;
    private final AuditService audit;
    private final PasswordResetService resets;

    OrganizationController(
            OrganizationService organizations,
            InvitationService invitations,
            AccessPolicy access,
            AuditService audit,
            PasswordResetService resets) {
        this.organizations = organizations;
        this.invitations = invitations;
        this.access = access;
        this.audit = audit;
        this.resets = resets;
    }

    @GetMapping
    PageResponse<AccessDtos.OrganizationView> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return organizations.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.OrganizationView create(@Valid @RequestBody AccessDtos.OrganizationInput input) {
        return organizations.create(input);
    }

    @GetMapping("/{orgId}")
    AccessDtos.OrganizationView get(@PathVariable UUID orgId) {
        return organizations.get(orgId);
    }

    @PatchMapping("/{orgId}")
    AccessDtos.OrganizationView edit(@PathVariable UUID orgId, @Valid @RequestBody AccessDtos.OrganizationEdit input) {
        return organizations.edit(orgId, input);
    }

    @GetMapping("/{orgId}/members")
    PageResponse<AccessDtos.MemberView> members(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return organizations.members(orgId, page, size);
    }

    @PatchMapping("/{orgId}/members/{userId}")
    AccessDtos.MemberView changeRole(
            @PathVariable UUID orgId, @PathVariable UUID userId, @Valid @RequestBody AccessDtos.RoleInput input) {
        return organizations.changeRole(orgId, userId, input.role());
    }

    @PostMapping("/{orgId}/members/{userId}/password-reset")
    @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.IssuedReset passwordReset(
            @PathVariable UUID orgId, @PathVariable UUID userId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return resets.issue(orgId, userId);
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable UUID orgId, @PathVariable UUID userId) {
        organizations.deactivate(orgId, userId);
    }

    @GetMapping("/{orgId}/invitations")
    PageResponse<AccessDtos.InvitationView> invitations(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return invitations.list(orgId, page, size);
    }

    @PostMapping("/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.CreatedInvitation invite(@PathVariable UUID orgId, @Valid @RequestBody AccessDtos.RoleInput input) {
        return invitations.create(orgId, input.role());
    }

    @PostMapping("/{orgId}/invitations/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID id) {
        invitations.revoke(orgId, id);
    }

    @GetMapping("/{orgId}/audit-events")
    PageResponse<AuditService.EventView> activity(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        access.require(orgId, Role.OWNER, Role.MANAGER);
        return audit.list(orgId, page, size);
    }
}
