package com.backroom.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.backroom.audit.AuditService;
import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import com.backroom.support.TestClockConfiguration;
import com.backroom.support.TestClockConfiguration.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Invitation links: expiry, revocation, single use, token privacy and concurrent acceptance. */
@IntegrationTest
@Import(TestClockConfiguration.class)
class InvitationIntegrationTests extends ApiTestSupport {
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
    void handlesInvitationExpiryRevocationReuseAndTokenPrivacy() throws Exception {
        var owner = register();
        var user = register();
        var org = org(owner);
        var base = base(org);
        var token = invitation(owner, base, "MANAGER");
        assertThat(jdbc.queryForObject(
                        "select token_hash from invitations where organization_id=?",
                        String.class,
                        UUID.fromString(org.get("id").asString())))
                .hasSize(64)
                .doesNotContain(token);
        assertThat(ok(owner, get(base + "/invitations"), null).toString())
                .doesNotContain(token, "tokenHash", "invitePath");
        assertThat(ok(user, post("/api/v1/invitations/preview"), Map.of("token", token))
                        .get("role")
                        .asString())
                .isEqualTo("MANAGER");
        join(user, token);
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", token))
                        .status())
                .isEqualTo(409);
        var activeMemberToken = invitation(owner, base, "EMPLOYEE");
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", activeMemberToken))
                        .status())
                .isEqualTo(409);
        assertThat(ok(user, get(base), null).get("role").asString()).isEqualTo("MANAGER");
        var created = ok(owner, post(base + "/invitations"), Map.of("role", "EMPLOYEE"));
        ok(
                owner,
                post(base + "/invitations/"
                        + created.get("invitation").get("id").asString() + "/revoke"),
                null);
        var revoked = created.get("invitePath").asString().split("token=")[1];
        assertThat(call(user, post("/api/v1/invitations/preview"), Map.of("token", revoked))
                        .status())
                .isEqualTo(409);
        var expires = invitation(owner, base, "EMPLOYEE");
        clock.advance(Duration.ofDays(8));
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", expires))
                        .status())
                .isEqualTo(409);
        assertThat(ok(owner, get(base + "/audit-events?size=100"), null).toString())
                .doesNotContain(token, activeMemberToken, revoked, expires, "password");
        assertThat(call(owner, post(base + "/invitations"), Map.of("role", "OWNER"))
                        .status())
                .isEqualTo(400);
    }

    @Test
    void onlyOneConcurrentRecipientCanConsumeAnInvitation() throws Exception {
        var owner = register();
        var a = register();
        var b = register();
        var org = org(owner);
        var base = base(org);
        var token = invitation(owner, base, "EMPLOYEE");
        var statuses = race(
                () -> call(a, post("/api/v1/invitations/accept"), Map.of("token", token))
                        .status(),
                () -> call(b, post("/api/v1/invitations/accept"), Map.of("token", token))
                        .status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject(
                        "select count(*) from memberships where organization_id=?",
                        Integer.class,
                        UUID.fromString(org.get("id").asString())))
                .isEqualTo(2);
    }

    List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return a.call();
            });
            var second = executor.submit(() -> {
                start.await();
                return b.call();
            });
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }
}
