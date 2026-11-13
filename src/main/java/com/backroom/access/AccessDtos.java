package com.backroom.access;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AccessDtos {
    private AccessDtos() {}

    public record Registration(
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @ValidPassword String password) {}

    public record UserView(UUID id, String email, String displayName) {}

    public record OrganizationInput(
            @NotBlank @Size(max = 120) String name) {}

    public record OrganizationEdit(
            @NotBlank @Size(max = 120) String name, @NotNull Long expectedVersion) {}

    public record OrganizationView(UUID id, String name, Long version, Role role) {}

    public record MemberView(UUID id, UUID userId, String displayName, String email, Role role, boolean active) {}

    public record RoleInput(@NotNull Role role) {}

    public record IssuedReset(UUID userId, String displayName, String resetPath, java.time.Instant expiresAt) {}

    public record ResetPreview(String displayName, String email, java.time.Instant expiresAt) {}

    public record ResetInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @ValidPassword String newPassword) {}

    public record TokenInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}

    public record InvitationView(UUID id, Role role, Instant expiresAt, String status, Instant acceptedAt) {}

    public record CreatedInvitation(InvitationView invitation, String invitePath) {}

    public record InvitationPreview(UUID organizationId, String organizationName, Role role, Instant expiresAt) {}
}
