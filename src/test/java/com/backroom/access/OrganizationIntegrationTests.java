package com.backroom.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.backroom.audit.AuditService;
import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import com.backroom.support.TestClockConfiguration;
import com.backroom.support.TestClockConfiguration.MutableClock;
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
import tools.jackson.databind.JsonNode;

/** Organizations and memberships: atomic creation, live role changes, the last owner, tenant isolation. */
@IntegrationTest
@Import(TestClockConfiguration.class)
class OrganizationIntegrationTests extends ApiTestSupport {
    @Autowired
    MutableClock clock;

    @MockitoSpyBean
    AuditService audit;

    @AfterEach
    void resetTimeAndAudit() {
        clock.reset();
        reset(audit);
    }

    JsonNode item(Client client, String base, String sku) throws Exception {
        return ok(client, post(base + "/items"), Map.of("name", "Coffee beans", "sku", sku, "unit", "KILOGRAM"));
    }

    @Test
    void createsOrganizationAtomicallyAndRollsBackWhenAuditFails() throws Exception {
        var owner = register();
        var created = org(owner);
        var orgId = UUID.fromString(created.get("id").asString());
        assertThat(jdbc.queryForObject(
                        "select role from memberships where organization_id=? and user_id=?",
                        String.class,
                        orgId,
                        owner.id()))
                .isEqualTo("OWNER");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where organization_id=? and actor_id=?",
                        Integer.class,
                        orgId,
                        owner.id()))
                .isEqualTo(1);
        var count = jdbc.queryForObject("select count(*) from organizations", Integer.class);
        doThrow(new IllegalStateException("Forced audit failure for rollback verification"))
                .when(audit)
                .record(any(), eq(owner.id()), anyString(), eq("ORGANIZATION_CREATED"), anyString(), any(), anyMap());
        assertThat(call(owner, post("/api/v1/organizations"), Map.of("name", "Must roll back"))
                        .status())
                .isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from organizations", Integer.class))
                .isEqualTo(count);
        assertThat(jdbc.queryForObject(
                        "select count(*) from memberships m left join organizations o on o.id=m.organization_id where o.id is null",
                        Integer.class))
                .isZero();
    }

    @Test
    void isolatesTenantsAcrossReadsListsWritesArchivesAndNestedReferences() throws Exception {
        var a = register();
        var b = register();
        var orgA = org(a);
        var orgB = org(b);
        var baseA = base(orgA);
        var baseB = base(orgB);
        var locA = location(a, baseA, "A room", null);
        var locB = location(b, baseB, "B room", null);
        var itemA = item(a, baseA, "SAME-SKU");
        var itemB = item(b, baseB, "SAME-SKU");
        for (var suffix : List.of(
                "",
                "/items",
                "/locations",
                "/members",
                "/invitations",
                "/audit-events",
                "/items/" + itemB.get("id").asString(),
                "/locations/" + locB.get("id").asString())) {
            assertThat(call(a, get(baseB + suffix), null).status()).isEqualTo(404);
        }
        assertThat(ok(a, get("/api/v1/organizations"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(ok(a, get(baseA + "/items"), null)
                        .get("items")
                        .get(0)
                        .get("id")
                        .asString())
                .isEqualTo(itemA.get("id").asString());
        assertThat(call(
                                a,
                                patch(baseA + "/items/" + itemB.get("id").asString()),
                                Map.of("name", "Attack", "unit", "EACH", "expectedVersion", 0))
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                a,
                                post(baseA + "/items/" + itemB.get("id").asString() + "/archive"),
                                Map.of("expectedVersion", 0))
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                a,
                                post(baseA + "/locations"),
                                Map.of(
                                        "name",
                                        "Attack",
                                        "type",
                                        "BIN",
                                        "parentId",
                                        locB.get("id").asString()))
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                a,
                                put(baseA + "/items/" + itemA.get("id").asString() + "/placements/"
                                        + locB.get("id").asString()),
                                null)
                        .status())
                .isEqualTo(404);
        assertThat(call(a, get(baseA + "/locations?parentId=" + locB.get("id").asString()), null)
                        .status())
                .isEqualTo(404);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into item_placements(id,version,created_at,organization_id,item_id,location_id,active) values(?,0,now(),?,?,?,true)",
                        UUID.randomUUID(),
                        UUID.fromString(orgA.get("id").asString()),
                        UUID.fromString(itemA.get("id").asString()),
                        UUID.fromString(locB.get("id").asString())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(locA.get("id")).isNotNull();
    }

    @Test
    void appliesCurrentRolesAndDeactivationToExistingSessions() throws Exception {
        var owner = register();
        var employee = register();
        var org = org(owner);
        var base = base(org);
        join(employee, invitation(owner, base, "EMPLOYEE"));
        assertThat(call(employee, get(base + "/items"), null).status()).isEqualTo(200);
        assertThat(call(employee, post(base + "/items"), Map.of("name", "Denied", "unit", "EACH"))
                        .status())
                .isEqualTo(403);
        assertThat(call(employee, get(base + "/audit-events"), null).status()).isEqualTo(403);
        ok(owner, patch(base + "/members/" + employee.id()), Map.of("role", "MANAGER"));
        assertThat(item(employee, base, "MANAGER-ITEM").get("id")).isNotNull();
        assertThat(call(employee, get(base + "/audit-events"), null).status()).isEqualTo(200);
        assertThat(call(employee, get(base + "/members"), null).status()).isEqualTo(403);
        ok(owner, delete(base + "/members/" + employee.id()), null);
        assertThat(call(employee, get(base + "/items"), null).status()).isEqualTo(404);
        join(employee, invitation(owner, base, "EMPLOYEE"));
        assertThat(call(employee, post(base + "/items"), Map.of("name", "Denied again", "unit", "EACH"))
                        .status())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject(
                        "select count(*) from memberships where organization_id=? and user_id=?",
                        Integer.class,
                        UUID.fromString(org.get("id").asString()),
                        employee.id()))
                .isEqualTo(1);
    }

    @Test
    void protectsLastOwnerIncludingConcurrentDemotions() throws Exception {
        var a = register();
        var b = register();
        var org = org(a);
        var base = base(org);
        assertThat(call(a, delete(base + "/members/" + a.id()), null).status()).isEqualTo(409);
        assertThat(call(a, patch(base + "/members/" + a.id()), Map.of("role", "EMPLOYEE"))
                        .status())
                .isEqualTo(409);
        join(b, invitation(a, base, "EMPLOYEE"));
        ok(a, patch(base + "/members/" + b.id()), Map.of("role", "OWNER"));
        var statuses = race(
                () -> call(a, patch(base + "/members/" + a.id()), Map.of("role", "EMPLOYEE"))
                        .status(),
                () -> call(b, patch(base + "/members/" + b.id()), Map.of("role", "EMPLOYEE"))
                        .status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject(
                        "select count(*) from memberships where organization_id=? and role='OWNER' and active=true",
                        Integer.class,
                        UUID.fromString(org.get("id").asString())))
                .isEqualTo(1);
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
