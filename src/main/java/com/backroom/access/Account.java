package com.backroom.access;

import com.backroom.shared.RecordEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class Account extends RecordEntity {
    @Column(nullable = false, length = 100)
    String displayName;

    @Column(nullable = false, length = 254)
    String email;

    @Column(nullable = false, length = 255)
    String passwordHash;
    /** Raised whenever the password changes; sessions signed in with an older value are rejected. */
    @Column(nullable = false)
    int credentialsVersion;

    protected Account() {}

    Account(String displayName, String email, String passwordHash) {
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    AccountPrincipal principal() {
        return new AccountPrincipal(id, email, displayName, passwordHash, credentialsVersion);
    }
}
