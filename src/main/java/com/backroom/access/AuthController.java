package com.backroom.access;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AccountService accounts;
    private final AccessPolicy access;
    AuthController(AccountService accounts, AccessPolicy access) { this.accounts = accounts; this.access = access; }

    record CsrfView(String token, String headerName, String parameterName) {}
    @GetMapping("/csrf")
    CsrfView csrf(CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName());
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AccessDtos.UserView register(@Valid @RequestBody AccessDtos.Registration input) { return accounts.register(input); }

    @GetMapping("/me")
    AccessDtos.UserView me(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var actor = access.actor();
        return new AccessDtos.UserView(actor.userId(), actor.email(), actor.displayName());
    }
}
