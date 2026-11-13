package com.backroom.access;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AccountPrincipal implements UserDetails, CredentialsContainer, Serializable {
    private static final long serialVersionUID = 1L;
    private final UUID userId;
    private final String email;
    private final String displayName;
    private String passwordHash;
    private final int credentialsVersion;

    public AccountPrincipal(
            UUID userId, String email, String displayName, String passwordHash, int credentialsVersion) {
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.credentialsVersion = credentialsVersion;
    }
    /** The account's credentials version when this session signed in. */
    public int credentialsVersion() {
        return credentialsVersion;
    }

    public UUID userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "AccountPrincipal[userId=" + userId + "]";
    }
}
