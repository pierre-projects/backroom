package com.backroom.access;

import com.backroom.shared.RecordEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "users")
class Account extends RecordEntity {
    @Column(nullable = false, length = 100) String displayName;
    @Column(nullable = false, length = 254) String email;
    @Column(nullable = false, length = 255) String passwordHash;
    protected Account() {}
    Account(String displayName, String email, String passwordHash) {
        this.displayName = displayName;
        this.email = email;
        this.passwordHash = passwordHash;
    }
    AccountPrincipal principal() { return new AccountPrincipal(id, email, displayName, passwordHash); }
}
