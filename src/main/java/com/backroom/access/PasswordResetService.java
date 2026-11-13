package com.backroom.access;

import static com.backroom.access.Role.OWNER;

import com.backroom.audit.AuditService;
import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account recovery without email: an Owner creates a one-time link for someone in their business and hands it over.
 * An Owner may only do this for people whose every active membership is in a business that Owner also owns, so a link
 * can never take over an account that someone else's business relies on.
 */
@Service
public class PasswordResetService {
    static final Duration LIFETIME = Duration.ofHours(24);
    /** How long a confirmed credentials version is trusted before it is read again. */
    static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

    private final PasswordResetRepository resets;
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final AccessPolicy access;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private record Checked(int version, Instant at) {}

    private final Map<UUID, Checked> versions = new ConcurrentHashMap<>();

    PasswordResetService(
            PasswordResetRepository resets,
            AccountRepository accounts,
            MembershipRepository memberships,
            AccessPolicy access,
            AuditService audit,
            PasswordEncoder encoder,
            Clock clock) {
        this.resets = resets;
        this.accounts = accounts;
        this.memberships = memberships;
        this.access = access;
        this.audit = audit;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional
    public AccessDtos.IssuedReset issue(UUID orgId, UUID userId) {
        access.lock(orgId, OWNER);
        var issuer = access.actor();
        memberships
                .findByOrganizationIdAndUserId(orgId, userId)
                .filter(member -> member.isActive())
                .orElseThrow(NotFoundException::new);
        for (var membership : memberships.findByUserIdAndActiveTrue(userId)) {
            var owner = memberships
                    .findByOrganizationIdAndUserId(membership.getOrganizationId(), issuer.userId())
                    .filter(member -> member.isActive() && member.getRole() == OWNER)
                    .isPresent();
            if (!owner) {
                throw new ConflictException(
                        "RESET_NOT_ALLOWED",
                        "This person also belongs to a business you don't own, so you can't reset their password. An Owner of every business they belong to can.");
            }
        }
        var account = accounts.findById(userId).orElseThrow(NotFoundException::new);
        var now = clock.instant();
        resets.findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(userId).forEach(open -> open.revoke(now));
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var reset = resets.saveAndFlush(
                new PasswordReset(userId, orgId, issuer.userId(), digest(token), now, now.plus(LIFETIME)));
        audit.record(
                orgId,
                issuer.userId(),
                issuer.displayName(),
                "PASSWORD_RESET_ISSUED",
                "USER",
                userId,
                Map.of("name", account.getDisplayName()));
        return new AccessDtos.IssuedReset(
                userId, account.getDisplayName(), "/reset#token=" + token, reset.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public AccessDtos.ResetPreview preview(String token) {
        var reset = resets.findByTokenHash(digest(token))
                .filter(found -> found.open(clock.instant()))
                .orElseThrow(PasswordResetService::unavailable);
        var account = accounts.findById(reset.getUserId()).orElseThrow(PasswordResetService::unavailable);
        return new AccessDtos.ResetPreview(account.getDisplayName(), account.getEmail(), reset.getExpiresAt());
    }

    /** Sets the new password, uses up the link, and ends every existing session for the account. Returns the email to sign in with. */
    @Transactional
    public String reset(String token, String newPassword) {
        var now = clock.instant();
        var reset = resets.lockByTokenHash(digest(token))
                .filter(found -> found.open(now))
                .orElseThrow(PasswordResetService::unavailable);
        var account = accounts.findById(reset.getUserId()).orElseThrow(PasswordResetService::unavailable);
        account.changePassword(encoder.encode(newPassword));
        reset.markUsed(now);
        resets.findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(reset.getUserId()).stream()
                .filter(other -> !other.getId().equals(reset.getId()))
                .forEach(other -> other.revoke(now));
        accounts.flush();
        // Forget the cached version now and again after commit, so no request can keep the old one alive.
        versions.remove(account.getId());
        var userId = account.getId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        versions.remove(userId);
                    }
                });
        audit.record(
                reset.getOrganizationId(),
                account.getId(),
                account.getDisplayName(),
                "PASSWORD_RESET_COMPLETED",
                "USER",
                account.getId(),
                Map.of("name", account.getDisplayName()));
        return account.getEmail();
    }

    /** False when the password changed after this session signed in. Cached briefly so most requests don't touch the database. */
    @Transactional(readOnly = true)
    public boolean current(AccountPrincipal principal) {
        var now = clock.instant();
        var cached = versions.get(principal.userId());
        if (cached == null || cached.at().plus(CHECK_INTERVAL).isBefore(now)) {
            var version = accounts.findById(principal.userId())
                    .map(account -> account.getCredentialsVersion())
                    .orElse(-1);
            cached = new Checked(version, now);
            versions.put(principal.userId(), cached);
        }
        return cached.version() == principal.credentialsVersion();
    }

    private static ConflictException unavailable() {
        return new ConflictException(
                "RESET_UNAVAILABLE",
                "This reset link has expired or has already been used. Ask an Owner for a new one.");
    }

    private static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
