package com.backroom.access;

import com.backroom.audit.AuditService;
import com.backroom.shared.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {
    private final OrganizationService organizations;
    private final InvitationService invitations;
    private final AccessPolicy access;
    private final AuditService audit;
    OrganizationController(OrganizationService organizations, InvitationService invitations, AccessPolicy access, AuditService audit) {
        this.organizations = organizations; this.invitations = invitations; this.access = access; this.audit = audit;
    }
    @GetMapping
    PageResponse<AccessDtos.OrganizationView> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return organizations.list(page, size);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.OrganizationView create(@Valid @RequestBody AccessDtos.OrganizationInput input) { return organizations.create(input); }
    @GetMapping("/{orgId}")
    AccessDtos.OrganizationView get(@PathVariable UUID orgId) { return organizations.get(orgId); }
    @PatchMapping("/{orgId}")
    AccessDtos.OrganizationView edit(@PathVariable UUID orgId, @Valid @RequestBody AccessDtos.OrganizationEdit input) { return organizations.edit(orgId, input); }
    @GetMapping("/{orgId}/members")
    PageResponse<AccessDtos.MemberView> members(@PathVariable UUID orgId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return organizations.members(orgId, page, size);
    }
    @PatchMapping("/{orgId}/members/{userId}")
    AccessDtos.MemberView changeRole(@PathVariable UUID orgId, @PathVariable UUID userId, @Valid @RequestBody AccessDtos.RoleInput input) {
        return organizations.changeRole(orgId, userId, input.role());
    }
    @DeleteMapping("/{orgId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable UUID orgId, @PathVariable UUID userId) { organizations.deactivate(orgId, userId); }
    @GetMapping("/{orgId}/invitations")
    PageResponse<AccessDtos.InvitationView> invitations(@PathVariable UUID orgId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return invitations.list(orgId, page, size);
    }
    @PostMapping("/{orgId}/invitations") @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.CreatedInvitation invite(@PathVariable UUID orgId, @Valid @RequestBody AccessDtos.RoleInput input) { return invitations.create(orgId, input.role()); }
    @PostMapping("/{orgId}/invitations/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID id) { invitations.revoke(orgId, id); }
    @GetMapping("/{orgId}/audit-events")
    PageResponse<AuditService.EventView> activity(@PathVariable UUID orgId, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        access.require(orgId, Role.OWNER, Role.MANAGER);
        return audit.list(orgId, page, size);
    }
}
