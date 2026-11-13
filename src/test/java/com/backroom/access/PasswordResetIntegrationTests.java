package com.backroom.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Owner-issued password reset links: one use, ending old sessions, ownership rules and expiry. */
@IntegrationTest
class PasswordResetIntegrationTests extends ApiTestSupport {
    record Shop(Client owner, String base, String item, String room, String shelfA, String shelfB) {}

    Shop shop(String unit) throws Exception {
        var owner = register();
        var base = base(org(owner));
        var room = location(owner, base, "Stockroom", null).get("id").asString();
        var a = location(owner, base, "Shelf A", room).get("id").asString();
        var b = location(owner, base, "Shelf B", room).get("id").asString();
        var item = item(owner, base, "Espresso beans", unit).get("id").asString();
        return new Shop(owner, base, item, room, a, b);
    }

    List<String> ids(JsonNode page, String field) {
        var values = new ArrayList<String>();
        page.get("items").forEach(row -> values.add(row.get(field).asString()));
        return values;
    }

    /** Ids of one result group from /search. */
    List<String> ids(JsonNode results, String group, String field) {
        var values = new ArrayList<String>();
        results.get(group).forEach(row -> values.add(row.get(field).asString()));
        return values;
    }

    /** A browser with a session and CSRF token but nobody signed in, as when someone opens a reset link. */
    Client anonymous() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        return new Client(
                null,
                (org.springframework.mock.web.MockHttpSession)
                        result.getRequest().getSession(false),
                tree(result).get("token").asString(),
                null);
    }

    int login(String email, String password) throws Exception {
        var visitor = anonymous();
        return mvc.perform(post("/api/v1/auth/login")
                        .session(visitor.session())
                        .header("X-CSRF-TOKEN", visitor.csrf())
                        .param("email", email)
                        .param("password", password))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    String token(JsonNode issued) {
        return issued.get("resetPath").asString().split("token=")[1];
    }

    @Test
    void ownerIssuedResetLinkSetsANewPasswordOnceAndEndsOldSessions() throws Exception {
        var shop = shop("EACH");
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        var issued = ok(shop.owner, post(shop.base + "/members/" + employee.id() + "/password-reset"), null);
        assertThat(issued.get("resetPath").asString()).startsWith("/reset#token=");
        var token = token(issued);

        var visitor = anonymous();
        var preview = ok(visitor, post("/api/v1/auth/password-reset/preview"), Map.of("token", token));
        assertThat(preview.get("email").asString()).isEqualTo(employee.email());
        assertThat(call(visitor, post("/api/v1/auth/password-reset"), Map.of("token", token, "newPassword", "short"))
                        .status())
                .isEqualTo(400);
        var newPassword = "A brand new passphrase 77";
        var done = ok(visitor, post("/api/v1/auth/password-reset"), Map.of("token", token, "newPassword", newPassword));
        assertThat(done.get("email").asString()).isEqualTo(employee.email());

        // The employee's existing session ends; only the new password signs in; the link works once.
        var ended = call(employee, get(shop.base + "/items"), null);
        assertThat(ended.status()).isEqualTo(401);
        assertThat(ended.code()).isEqualTo("SESSION_ENDED");
        assertThat(login(employee.email(), PASSWORD)).isEqualTo(401);
        assertThat(login(employee.email(), newPassword)).isEqualTo(200);
        assertThat(call(
                                anonymous(),
                                post("/api/v1/auth/password-reset"),
                                Map.of("token", token, "newPassword", "Another passphrase 1234"))
                        .code())
                .isEqualTo("RESET_UNAVAILABLE");
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", token))
                        .code())
                .isEqualTo("RESET_UNAVAILABLE");
        // Other people's sessions are untouched.
        assertThat(call(shop.owner, get(shop.base + "/items"), null).status()).isEqualTo(200);

        // Only the token's hash is stored, and neither the token nor the password reaches the audit log.
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_resets where token_hash = ?", Integer.class, token))
                .isZero();
        var audit = jdbc.queryForList(
                "select action || ' ' || metadata::text from audit_events where resource_id = ? and action like 'PASSWORD_RESET_%' order by created_at",
                String.class, employee.id());
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).startsWith("PASSWORD_RESET_ISSUED");
        assertThat(audit.get(1)).startsWith("PASSWORD_RESET_COMPLETED");
        assertThat(String.join(" ", audit)).doesNotContain(token).doesNotContain(newPassword);
    }

    @Test
    void resetLinksFollowOwnershipRulesAndExpire() throws Exception {
        var shop = shop("EACH");
        var manager = register();
        join(manager, invitation(shop.owner, shop.base, "MANAGER"));
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(call(manager, post(shop.base + "/members/" + employee.id() + "/password-reset"), null)
                        .status())
                .isEqualTo(403);
        assertThat(call(employee, post(shop.base + "/members/" + manager.id() + "/password-reset"), null)
                        .status())
                .isEqualTo(403);
        var stranger = register();
        assertThat(call(shop.owner, post(shop.base + "/members/" + stranger.id() + "/password-reset"), null)
                        .status())
                .isEqualTo(404);

        // Someone who also works for a business this Owner doesn't own can't be reset from here.
        var otherOwner = register();
        var otherBase = base(org(otherOwner));
        join(employee, invitation(otherOwner, otherBase, "EMPLOYEE"));
        var refused = call(shop.owner, post(shop.base + "/members/" + employee.id() + "/password-reset"), null);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("RESET_NOT_ALLOWED");
        // ...but an Owner of both businesses can.
        var secondBase = base(org(shop.owner));
        join(manager, invitation(shop.owner, secondBase, "EMPLOYEE"));
        var first = token(ok(shop.owner, post(shop.base + "/members/" + manager.id() + "/password-reset"), null));
        var second = token(ok(shop.owner, post(secondBase + "/members/" + manager.id() + "/password-reset"), null));
        // A newer link replaces the older one.
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", first))
                        .code())
                .isEqualTo("RESET_UNAVAILABLE");
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", second))
                        .status())
                .isEqualTo(200);
        jdbc.update(
                "update password_resets set created_at = now() - interval '2 days', expires_at = now() - interval '1 day' where user_id = ? and revoked_at is null",
                manager.id());
        assertThat(call(
                                anonymous(),
                                post("/api/v1/auth/password-reset"),
                                Map.of("token", second, "newPassword", "Expired link passphrase"))
                        .code())
                .isEqualTo("RESET_UNAVAILABLE");
        assertThat(login(manager.email(), PASSWORD)).isEqualTo(200);
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", "not-a-token"))
                        .status())
                .isEqualTo(400);
    }
}
