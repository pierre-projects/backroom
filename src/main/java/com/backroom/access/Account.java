package com.backroom.access;

import com.backroom.shared.persistence.RecordEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class Account extends RecordEntity {
    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;
    /** Raised whenever the password changes; sessions signed in with an older value are rejected. */
    @Column(nullable = false)
    private int credentialsVersion;

    protected Account() {}

    Account(String displayName, String email, String passwordHash) {
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    AccountPrincipal principal() {
        return new AccountPrincipal(id, email, displayName, passwordHash, credentialsVersion);
    }

    /** Sets a new password and invalidates every session issued with the old credentials. */
    void changePassword(String newPasswordHash) {
        passwordHash = newPasswordHash;
        credentialsVersion++;
    }

    String getDisplayName() {
        return displayName;
    }

    String getEmail() {
        return email;
    }

    int getCredentialsVersion() {
        return credentialsVersion;
    }
}
