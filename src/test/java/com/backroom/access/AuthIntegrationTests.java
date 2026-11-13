package com.backroom.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backroom.audit.AuditService;
import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import com.backroom.support.TestClockConfiguration;
import com.backroom.support.TestClockConfiguration.MutableClock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Registration, password rules, sign-in sessions, CSRF and sign-out. */
@IntegrationTest
@Import(TestClockConfiguration.class)
class AuthIntegrationTests extends ApiTestSupport {
    @Autowired
    MutableClock clock;

    @MockitoSpyBean
    AuditService audit;

    @AfterEach
    void resetTimeAndAudit() {
        clock.reset();
        reset(audit);
    }

    @Test
    void registersNormalizedAccountsAndEnforcesPasswordRulesAndUniqueness() throws Exception {
        var user = register();
        var hash = jdbc.queryForObject("select password_hash from users where id=?", String.class, user.id());
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("select email from users where id=?", String.class, user.id()))
                .isEqualTo(user.email());
        assertThat(call(
                                user,
                                post("/api/v1/auth/register"),
                                Map.of(
                                        "email",
                                        user.email().toUpperCase(Locale.ROOT),
                                        "displayName",
                                        "Duplicate",
                                        "password",
                                        PASSWORD))
                        .status())
                .isEqualTo(409);
        for (var password : List.of("short", "😀".repeat(19), "a".repeat(73))) {
            var result = call(
                    user,
                    post("/api/v1/auth/register"),
                    Map.of(
                            "email",
                            UUID.randomUUID() + "@example.test",
                            "displayName",
                            "Invalid",
                            "password",
                            password));
            assertThat(result.status()).isEqualTo(400);
            assertThat(result.json().get("fieldErrors").has("password")).isTrue();
            assertThat(result.json().toString()).doesNotContain(password);
        }
    }

    @Test
    void authenticatesWithActualSessionsRefreshesCsrfAndInvalidatesLogout() throws Exception {
        var csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var anonymous = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var oldToken = tree(csrfResult).get("token").asString();
        var user = register();
        mvc.perform(post("/api/v1/auth/login")
                        .session(anonymous)
                        .header("X-CSRF-TOKEN", oldToken)
                        .param("email", user.email())
                        .param("password", "incorrect password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
        var before = anonymous.getId();
        mvc.perform(post("/api/v1/auth/login")
                        .session(anonymous)
                        .header("X-CSRF-TOKEN", oldToken)
                        .with(request -> {
                            request.setRequestedSessionId(anonymous.getId());
                            request.setRequestedSessionIdValid(true);
                            return request;
                        })
                        .param("email", user.email())
                        .param("password", PASSWORD))
                .andExpect(status().isOk());
        assertThat(anonymous.getId()).isNotEqualTo(before);
        mvc.perform(post("/api/v1/organizations")
                        .session(anonymous)
                        .header("X-CSRF-TOKEN", oldToken)
                        .contentType("application/json")
                        .content("{\"name\":\"Stale CSRF\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
        ok(user, get("/api/v1/auth/me"), null);
        ok(user, post("/api/v1/auth/logout"), null);
        assertThat(user.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }
}
