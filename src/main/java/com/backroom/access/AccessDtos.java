package com.backroom.access;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class AccessDtos {
    private AccessDtos() {}
    public record Registration(@NotBlank @Size(max=100) String displayName,
                               @NotBlank @Email @Size(max=254) String email,
                               @ValidPassword String password) {}
    public record UserView(UUID id, String email, String displayName) {}
    public record OrganizationInput(@NotBlank @Size(max=120) String name) {}
    public record OrganizationEdit(@NotBlank @Size(max=120) String name, @NotNull Long expectedVersion) {}
    public record OrganizationView(UUID id, String name, Long version, Role role) {}
    public record MemberView(UUID id, UUID userId, String displayName, String email, Role role, boolean active) {}
    public record RoleInput(@NotNull Role role) {}
    public record TokenInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    public record InvitationView(UUID id, Role role, Instant expiresAt, String status, Instant acceptedAt) {}
    public record CreatedInvitation(InvitationView invitation, String invitePath) {}
    public record InvitationPreview(UUID organizationId, String organizationName, Role role, Instant expiresAt) {}
}
