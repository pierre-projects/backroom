package com.backroom.access;

import com.backroom.shared.ApiException;
import java.util.Locale;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements UserDetailsService {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    AccountService(AccountRepository accounts, PasswordEncoder encoder) { this.accounts = accounts; this.encoder = encoder; }

    static String normalizeEmail(String value) { return value.strip().toLowerCase(Locale.ROOT); }

    @Transactional
    public AccessDtos.UserView register(AccessDtos.Registration input) {
        var email = normalizeEmail(input.email());
        if (accounts.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_IN_USE", "An account with this email already exists.");
        }
        var account = accounts.saveAndFlush(new Account(input.displayName().strip(), email, encoder.encode(input.password())));
        return new AccessDtos.UserView(account.getId(), account.email, account.displayName);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return accounts.findByEmail(normalizeEmail(email)).map(Account::principal)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    }
}
