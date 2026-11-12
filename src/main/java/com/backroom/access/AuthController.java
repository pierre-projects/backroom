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
    private final PasswordResetService resets;
    AuthController(AccountService accounts, AccessPolicy access, PasswordResetService resets) { this.accounts = accounts; this.access = access; this.resets = resets; }

    record TokenOnly(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    record ResetDone(String email) {}
    @PostMapping("/password-reset/preview")
    AccessDtos.ResetPreview previewReset(@Valid @RequestBody TokenOnly input, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return resets.preview(input.token());
    }
    @PostMapping("/password-reset")
    ResetDone reset(@Valid @RequestBody AccessDtos.ResetInput input, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new ResetDone(resets.reset(input.token(), input.newPassword()));
    }

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
