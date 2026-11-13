package com.backroom;

import com.backroom.audit.AuditService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({InfrastructureIntegrationTests.PostgresConfiguration.class, FoundationIntegrationTests.TimeConfiguration.class})
class FoundationIntegrationTests {
    static final String PASSWORD = "Correct horse staple 42!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean AuditService audit;

    record Client(UUID id, MockHttpSession session, String csrf, String email) {}
    record Response(int status, JsonNode json) {}

    @AfterEach
    void resetTimeAndAudit() { clock.reset(); reset(audit); }

    Client register() throws Exception {
        var email = "person-" + UUID.randomUUID() + "@example.test";
        var csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var csrf = tree(csrfResult).get("token").asString();
        var input = Map.of("email", email.toUpperCase(Locale.ROOT), "displayName", "Test Person", "password", PASSWORD);
        var created = mvc.perform(post("/api/v1/auth/register").session(session).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated()).andReturn();
        var anonymousId = session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", csrf)
                .with(request -> { request.setRequestedSessionId(session.getId()); request.setRequestedSessionIdValid(true); return request; })
                .param("email", " " + email.toUpperCase(Locale.ROOT) + " ").param("password", PASSWORD))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(anonymousId);
        var refreshed = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return new Client(UUID.fromString(tree(created).get("id").asString()), session, tree(refreshed).get("token").asString(), email);
    }
    JsonNode tree(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
    Response call(Client client, MockHttpServletRequestBuilder request, Object input) throws Exception {
        request.session(client.session).header("X-CSRF-TOKEN", client.csrf);
        if (input != null) request.contentType("application/json").content(mapper.writeValueAsString(input));
        var result = mvc.perform(request).andReturn();
        var text = result.getResponse().getContentAsString();
        return new Response(result.getResponse().getStatus(), text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text));
    }
    JsonNode ok(Client client, MockHttpServletRequestBuilder request, Object input) throws Exception {
        var response = call(client, request, input);
        assertThat(response.status).withFailMessage(response.json.toString()).isBetween(200, 299);
        return response.json;
    }
    JsonNode org(Client client) throws Exception { return ok(client, post("/api/v1/organizations"), Map.of("name", "Business " + UUID.randomUUID())); }
    String base(JsonNode org) { return "/api/v1/organizations/" + org.get("id").asString(); }
    JsonNode location(Client client, String base, String name, String parentId) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("name", name); data.put("type", "ROOM"); data.put("parentId", parentId);
        return ok(client, post(base + "/locations"), data);
    }
    JsonNode item(Client client, String base, String sku) throws Exception {
        return ok(client, post(base + "/items"), Map.of("name", "Coffee beans", "sku", sku, "unit", "KILOGRAM"));
    }
    String invitation(Client owner, String base, String role) throws Exception {
        return ok(owner, post(base + "/invitations"), Map.of("role", role)).get("invitePath").asString().split("token=")[1];
    }
    void join(Client user, String token) throws Exception { ok(user, post("/api/v1/invitations/accept"), Map.of("token", token)); }

    @Test
    void registersNormalizedAccountsAndEnforcesPasswordRulesAndUniqueness() throws Exception {
        var user = register();
        var hash = jdbc.queryForObject("select password_hash from users where id=?", String.class, user.id);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("select email from users where id=?", String.class, user.id)).isEqualTo(user.email);
        assertThat(call(user, post("/api/v1/auth/register"), Map.of("email", user.email.toUpperCase(Locale.ROOT),
                "displayName", "Duplicate", "password", PASSWORD)).status).isEqualTo(409);
        for (var password : List.of("short", "😀".repeat(19), "a".repeat(73))) {
            var result = call(user, post("/api/v1/auth/register"), Map.of("email", UUID.randomUUID()+"@example.test", "displayName", "Invalid", "password", password));
            assertThat(result.status).isEqualTo(400);
            assertThat(result.json.get("fieldErrors").has("password")).isTrue();
            assertThat(result.json.toString()).doesNotContain(password);
        }
    }

    @Test
    void authenticatesWithActualSessionsRefreshesCsrfAndInvalidatesLogout() throws Exception {
        var csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var anonymous = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var oldToken = tree(csrfResult).get("token").asString();
        var user = register();
        mvc.perform(post("/api/v1/auth/login").session(anonymous).header("X-CSRF-TOKEN", oldToken)
                .param("email", user.email).param("password", "incorrect password"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
        var before = anonymous.getId();
        mvc.perform(post("/api/v1/auth/login").session(anonymous).header("X-CSRF-TOKEN", oldToken)
                .with(request -> { request.setRequestedSessionId(anonymous.getId()); request.setRequestedSessionIdValid(true); return request; })
                .param("email", user.email).param("password", PASSWORD)).andExpect(status().isOk());
        assertThat(anonymous.getId()).isNotEqualTo(before);
        mvc.perform(post("/api/v1/organizations").session(anonymous).header("X-CSRF-TOKEN", oldToken)
                .contentType("application/json").content("{\"name\":\"Stale CSRF\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CSRF_INVALID"));
        ok(user, get("/api/v1/auth/me"), null);
        ok(user, post("/api/v1/auth/logout"), null);
        assertThat(user.session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void createsOrganizationAtomicallyAndRollsBackWhenAuditFails() throws Exception {
        var owner = register();
        var created = org(owner);
        var orgId = UUID.fromString(created.get("id").asString());
        assertThat(jdbc.queryForObject("select role from memberships where organization_id=? and user_id=?", String.class, orgId, owner.id)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where organization_id=? and actor_id=?", Integer.class, orgId, owner.id)).isEqualTo(1);
        var count = jdbc.queryForObject("select count(*) from organizations", Integer.class);
        doThrow(new IllegalStateException("Forced audit failure for rollback verification")).when(audit)
                .record(any(), eq(owner.id), anyString(), eq("ORGANIZATION_CREATED"), anyString(), any(), anyMap());
        assertThat(call(owner, post("/api/v1/organizations"), Map.of("name", "Must roll back")).status).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from organizations", Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from memberships m left join organizations o on o.id=m.organization_id where o.id is null", Integer.class)).isZero();
    }

    @Test
    void isolatesTenantsAcrossReadsListsWritesArchivesAndNestedReferences() throws Exception {
        var a=register(); var b=register();
        var orgA=org(a); var orgB=org(b); var baseA=base(orgA); var baseB=base(orgB);
        var locA=location(a, baseA, "A room", null); var locB=location(b, baseB, "B room", null);
        var itemA=item(a, baseA, "SAME-SKU"); var itemB=item(b, baseB, "SAME-SKU");
        for (var suffix : List.of("", "/items", "/locations", "/members", "/invitations", "/audit-events",
                "/items/"+itemB.get("id").asString(), "/locations/"+locB.get("id").asString())) {
            assertThat(call(a, get(baseB+suffix), null).status).isEqualTo(404);
        }
        assertThat(ok(a, get("/api/v1/organizations"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(a, get(baseA+"/items"), null).get("items").get(0).get("id").asString()).isEqualTo(itemA.get("id").asString());
        assertThat(call(a, patch(baseA+"/items/"+itemB.get("id").asString()), Map.of("name", "Attack", "unit", "EACH", "expectedVersion", 0)).status).isEqualTo(404);
        assertThat(call(a, post(baseA+"/items/"+itemB.get("id").asString()+"/archive"), Map.of("expectedVersion", 0)).status).isEqualTo(404);
        assertThat(call(a, post(baseA+"/locations"), Map.of("name", "Attack", "type", "BIN", "parentId", locB.get("id").asString())).status).isEqualTo(404);
        assertThat(call(a, put(baseA+"/items/"+itemA.get("id").asString()+"/placements/"+locB.get("id").asString()), null).status).isEqualTo(404);
        assertThat(call(a, get(baseA+"/locations?parentId="+locB.get("id").asString()), null).status).isEqualTo(404);
        assertThatThrownBy(() -> jdbc.update("insert into item_placements(id,version,created_at,organization_id,item_id,location_id,active) values(?,0,now(),?,?,?,true)",
                UUID.randomUUID(), UUID.fromString(orgA.get("id").asString()), UUID.fromString(itemA.get("id").asString()), UUID.fromString(locB.get("id").asString())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(locA.get("id")).isNotNull();
    }

    @Test
    void appliesCurrentRolesAndDeactivationToExistingSessions() throws Exception {
        var owner=register(); var employee=register(); var org=org(owner); var base=base(org);
        join(employee, invitation(owner, base, "EMPLOYEE"));
        assertThat(call(employee, get(base+"/items"), null).status).isEqualTo(200);
        assertThat(call(employee, post(base+"/items"), Map.of("name", "Denied", "unit", "EACH")).status).isEqualTo(403);
        assertThat(call(employee, get(base+"/audit-events"), null).status).isEqualTo(403);
        ok(owner, patch(base+"/members/"+employee.id), Map.of("role", "MANAGER"));
        assertThat(item(employee, base, "MANAGER-ITEM").get("id")).isNotNull();
        assertThat(call(employee, get(base+"/audit-events"), null).status).isEqualTo(200);
        assertThat(call(employee, get(base+"/members"), null).status).isEqualTo(403);
        ok(owner, delete(base+"/members/"+employee.id), null);
        assertThat(call(employee, get(base+"/items"), null).status).isEqualTo(404);
        join(employee, invitation(owner, base, "EMPLOYEE"));
        assertThat(call(employee, post(base+"/items"), Map.of("name", "Denied again", "unit", "EACH")).status).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from memberships where organization_id=? and user_id=?", Integer.class, UUID.fromString(org.get("id").asString()), employee.id)).isEqualTo(1);
    }

    @Test
    void protectsLastOwnerIncludingConcurrentDemotions() throws Exception {
        var a=register(); var b=register(); var org=org(a); var base=base(org);
        assertThat(call(a, delete(base+"/members/"+a.id), null).status).isEqualTo(409);
        assertThat(call(a, patch(base+"/members/"+a.id), Map.of("role", "EMPLOYEE")).status).isEqualTo(409);
        join(b, invitation(a, base, "EMPLOYEE"));
        ok(a, patch(base+"/members/"+b.id), Map.of("role", "OWNER"));
        var statuses = race(() -> call(a, patch(base+"/members/"+a.id), Map.of("role", "EMPLOYEE")).status,
                () -> call(b, patch(base+"/members/"+b.id), Map.of("role", "EMPLOYEE")).status);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from memberships where organization_id=? and role='OWNER' and active=true", Integer.class, UUID.fromString(org.get("id").asString()))).isEqualTo(1);
    }

    @Test
    void handlesInvitationExpiryRevocationReuseAndTokenPrivacy() throws Exception {
        var owner=register(); var user=register(); var org=org(owner); var base=base(org);
        var token=invitation(owner, base, "MANAGER");
        assertThat(jdbc.queryForObject("select token_hash from invitations where organization_id=?", String.class, UUID.fromString(org.get("id").asString()))).hasSize(64).doesNotContain(token);
        assertThat(ok(owner, get(base+"/invitations"), null).toString()).doesNotContain(token, "tokenHash", "invitePath");
        assertThat(ok(user, post("/api/v1/invitations/preview"), Map.of("token", token)).get("role").asString()).isEqualTo("MANAGER");
        join(user, token);
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", token)).status).isEqualTo(409);
        var activeMemberToken=invitation(owner, base, "EMPLOYEE");
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", activeMemberToken)).status).isEqualTo(409);
        assertThat(ok(user, get(base), null).get("role").asString()).isEqualTo("MANAGER");
        var created=ok(owner, post(base+"/invitations"), Map.of("role", "EMPLOYEE"));
        ok(owner, post(base+"/invitations/"+created.get("invitation").get("id").asString()+"/revoke"), null);
        var revoked=created.get("invitePath").asString().split("token=")[1];
        assertThat(call(user, post("/api/v1/invitations/preview"), Map.of("token", revoked)).status).isEqualTo(409);
        var expires=invitation(owner, base, "EMPLOYEE"); clock.advance(Duration.ofDays(8));
        assertThat(call(user, post("/api/v1/invitations/accept"), Map.of("token", expires)).status).isEqualTo(409);
        assertThat(ok(owner, get(base+"/audit-events?size=100"), null).toString()).doesNotContain(token, activeMemberToken, revoked, expires, "password");
        assertThat(call(owner, post(base+"/invitations"), Map.of("role", "OWNER")).status).isEqualTo(400);
    }

    @Test
    void onlyOneConcurrentRecipientCanConsumeAnInvitation() throws Exception {
        var owner=register(); var a=register(); var b=register(); var org=org(owner); var base=base(org);
        var token=invitation(owner, base, "EMPLOYEE");
        var statuses=race(() -> call(a, post("/api/v1/invitations/accept"), Map.of("token", token)).status,
                () -> call(b, post("/api/v1/invitations/accept"), Map.of("token", token)).status);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from memberships where organization_id=?", Integer.class, UUID.fromString(org.get("id").asString()))).isEqualTo(2);
    }

    @Test
    void protectsHierarchyAndPlacementsAndDetectsStaleMetadata() throws Exception {
        var owner=register(); var org=org(owner); var base=base(org);
        var root=location(owner, base, "Stockroom", null);
        var child=location(owner, base, "Shelf", root.get("id").asString());
        var product=item(owner, base, " bags ");
        assertThat(ok(owner, get(base+"/locations?rootsOnly=true"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(owner, get(base+"/locations?leafOnly=true"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(product.get("sku").asString()).isEqualTo("BAGS");
        var rootId=root.get("id").asString(); var childId=child.get("id").asString(); var itemId=product.get("id").asString();
        assertThat(call(owner, patch(base+"/locations/"+rootId), Map.of("name", "Cycle", "type", "ROOM", "parentId", childId, "expectedVersion", 0)).status).isEqualTo(409);
        assertThat(call(owner, put(base+"/items/"+itemId+"/placements/"+rootId), null).status).isEqualTo(409);
        ok(owner, put(base+"/items/"+itemId+"/placements/"+childId), null);
        assertThat(call(owner, post(base+"/locations"), Map.of("name", "Not a leaf", "type", "BIN", "parentId", childId)).status).isEqualTo(409);
        assertThat(call(owner, post(base+"/locations/"+childId+"/archive"), Map.of("expectedVersion", 0)).status).isEqualTo(409);
        var edited=ok(owner, patch(base+"/items/"+itemId), Map.of("name", "New name", "unit", "GRAM", "sku", "BAGS", "expectedVersion", 0));
        assertThat(edited.get("version").asInt()).isEqualTo(1);
        assertThat(call(owner, patch(base+"/items/"+itemId), Map.of("name", "Stale", "unit", "GRAM", "expectedVersion", 0)).status).isEqualTo(409);
        ok(owner, post(base+"/items/"+itemId+"/archive"), Map.of("expectedVersion", 1));
        assertThat(ok(owner, get(base+"/items/"+itemId+"/placements"), null).get("totalElements").asInt()).isZero();
        assertThat(call(owner, post(base+"/items"), Map.of("name", "Duplicate", "sku", "bags", "unit", "EACH")).status).isEqualTo(409);
        ok(owner, post(base+"/locations/"+childId+"/archive"), Map.of("expectedVersion", 0));
        assertThat(call(owner, post(base+"/locations"), Map.of("name", "Archived parent", "type", "BIN", "parentId", childId)).status).isEqualTo(409);
        assertThat(ok(owner, get(base+"/items"), null).get("totalElements").asInt()).isZero();
        assertThat(ok(owner, get(base+"/items?includeArchived=true"), null).get("totalElements").asInt()).isEqualTo(1);
        assertThat(call(owner, get(base+"/items?size=101"), null).status).isEqualTo(400);
        assertThat(ok(owner, get(base+"/audit-events?size=100"), null).get("items").get(0).get("actorId").asString()).isEqualTo(owner.id.toString());
    }

    @Test
    void serializesCompetingHierarchyAndPlacementChanges() throws Exception {
        var owner=register(); var org=org(owner); var base=base(org);
        var a=location(owner, base, "A", null); var b=location(owner, base, "B", null);
        var statuses=race(
                () -> call(owner, patch(base+"/locations/"+a.get("id").asString()), Map.of("name", "A", "type", "ROOM", "parentId", b.get("id").asString(), "expectedVersion", 0)).status,
                () -> call(owner, patch(base+"/locations/"+b.get("id").asString()), Map.of("name", "B", "type", "ROOM", "parentId", a.get("id").asString(), "expectedVersion", 0)).status);
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var leaf=location(owner, base, "Leaf", null); var product=item(owner, base, "RACE");
        var changes=race(
                () -> call(owner, put(base+"/items/"+product.get("id").asString()+"/placements/"+leaf.get("id").asString()), null).status,
                () -> call(owner, post(base+"/locations"), Map.of("name", "Child", "type", "BIN", "parentId", leaf.get("id").asString())).status);
        assertThat(changes.stream().filter(value -> value == 409).count()).isEqualTo(1);
        assertThat(changes.stream().filter(value -> value >= 200 && value < 300).count()).isEqualTo(1);
    }

    @Test
    void preservesSpaRoutesAndReturnsProblemsForUnknownApiAndAssets() throws Exception {
        var user=register();
        mvc.perform(get("/app/organizations/"+UUID.randomUUID()+"/items")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        assertThat(call(user, get("/api/v1/unknown"), null).status).isEqualTo(404);
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void upgradesAnEmptyPhaseZeroSchemaWithoutResettingItsHistory() {
        var schema="upgrade_"+UUID.randomUUID().toString().replace("-", "");
        var source=Objects.requireNonNull(jdbc.getDataSource());
        var phaseZero=Flyway.configure().dataSource(source).defaultSchema(schema).locations("classpath:phase0-empty").load();
        phaseZero.migrate();
        assertThat(jdbc.queryForObject("select to_regclass(?)::text", String.class, schema+".flyway_schema_history")).isNotNull();
        var upgraded=Flyway.configure().dataSource(source).defaultSchema(schema).locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(11);
        assertThat(upgraded.info().applied()).hasSize(12); // Schema creation plus eleven versioned migrations.
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }

    List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> { start.await(); return a.call(); });
            var second=executor.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    static class MutableClock extends Clock {
        private static final Instant INITIAL=Instant.parse("2026-11-12T03:00:00Z");
        private final AtomicReference<Instant> now=new AtomicReference<>(INITIAL);
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        void reset() { now.set(INITIAL); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
}
