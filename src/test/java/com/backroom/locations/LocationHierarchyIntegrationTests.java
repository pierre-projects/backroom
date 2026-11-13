package com.backroom.locations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.backroom.audit.AuditService;
import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import com.backroom.support.TestClockConfiguration;
import com.backroom.support.TestClockConfiguration.MutableClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** Location hierarchy and placement rules, stale edits, and competing concurrent changes. */
@IntegrationTest
@Import(TestClockConfiguration.class)
class LocationHierarchyIntegrationTests extends ApiTestSupport {
    @Autowired
    MutableClock clock;

    @MockitoSpyBean
    AuditService audit;

    @AfterEach
    void resetTimeAndAudit() {
        clock.reset();
        reset(audit);
    }

    /** Always a ROOM, even when nested, unlike the shared location helper. */
    JsonNode room(Client client, String base, String name, String parentId) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("name", name);
        data.put("type", "ROOM");
        data.put("parentId", parentId);
        return ok(client, post(base + "/locations"), data);
    }

    JsonNode item(Client client, String base, String sku) throws Exception {
        return ok(client, post(base + "/items"), Map.of("name", "Coffee beans", "sku", sku, "unit", "KILOGRAM"));
    }

    @Test
    void protectsHierarchyAndPlacementsAndDetectsStaleMetadata() throws Exception {
        var owner = register();
        var org = org(owner);
        var base = base(org);
        var root = room(owner, base, "Stockroom", null);
        var child = room(owner, base, "Shelf", root.get("id").asString());
        var product = item(owner, base, " bags ");
        assertThat(ok(owner, get(base + "/locations?rootsOnly=true"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(ok(owner, get(base + "/locations?leafOnly=true"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(product.get("sku").asString()).isEqualTo("BAGS");
        var rootId = root.get("id").asString();
        var childId = child.get("id").asString();
        var itemId = product.get("id").asString();
        assertThat(call(
                                owner,
                                patch(base + "/locations/" + rootId),
                                Map.of("name", "Cycle", "type", "ROOM", "parentId", childId, "expectedVersion", 0))
                        .status())
                .isEqualTo(409);
        assertThat(call(owner, put(base + "/items/" + itemId + "/placements/" + rootId), null)
                        .status())
                .isEqualTo(409);
        ok(owner, put(base + "/items/" + itemId + "/placements/" + childId), null);
        assertThat(call(
                                owner,
                                post(base + "/locations"),
                                Map.of("name", "Not a leaf", "type", "BIN", "parentId", childId))
                        .status())
                .isEqualTo(409);
        assertThat(call(owner, post(base + "/locations/" + childId + "/archive"), Map.of("expectedVersion", 0))
                        .status())
                .isEqualTo(409);
        var edited = ok(
                owner,
                patch(base + "/items/" + itemId),
                Map.of("name", "New name", "unit", "GRAM", "sku", "BAGS", "expectedVersion", 0));
        assertThat(edited.get("version").asInt()).isEqualTo(1);
        assertThat(call(
                                owner,
                                patch(base + "/items/" + itemId),
                                Map.of("name", "Stale", "unit", "GRAM", "expectedVersion", 0))
                        .status())
                .isEqualTo(409);
        ok(owner, post(base + "/items/" + itemId + "/archive"), Map.of("expectedVersion", 1));
        assertThat(ok(owner, get(base + "/items/" + itemId + "/placements"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();
        assertThat(call(owner, post(base + "/items"), Map.of("name", "Duplicate", "sku", "bags", "unit", "EACH"))
                        .status())
                .isEqualTo(409);
        ok(owner, post(base + "/locations/" + childId + "/archive"), Map.of("expectedVersion", 0));
        assertThat(call(
                                owner,
                                post(base + "/locations"),
                                Map.of("name", "Archived parent", "type", "BIN", "parentId", childId))
                        .status())
                .isEqualTo(409);
        assertThat(ok(owner, get(base + "/items"), null).get("totalElements").asInt())
                .isZero();
        assertThat(ok(owner, get(base + "/items?includeArchived=true"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(call(owner, get(base + "/items?size=101"), null).status()).isEqualTo(400);
        assertThat(ok(owner, get(base + "/audit-events?size=100"), null)
                        .get("items")
                        .get(0)
                        .get("actorId")
                        .asString())
                .isEqualTo(owner.id().toString());
    }

    @Test
    void serializesCompetingHierarchyAndPlacementChanges() throws Exception {
        var owner = register();
        var org = org(owner);
        var base = base(org);
        var a = room(owner, base, "A", null);
        var b = room(owner, base, "B", null);
        var statuses = race(
                () -> call(
                                owner,
                                patch(base + "/locations/" + a.get("id").asString()),
                                Map.of(
                                        "name",
                                        "A",
                                        "type",
                                        "ROOM",
                                        "parentId",
                                        b.get("id").asString(),
                                        "expectedVersion",
                                        0))
                        .status(),
                () -> call(
                                owner,
                                patch(base + "/locations/" + b.get("id").asString()),
                                Map.of(
                                        "name",
                                        "B",
                                        "type",
                                        "ROOM",
                                        "parentId",
                                        a.get("id").asString(),
                                        "expectedVersion",
                                        0))
                        .status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var leaf = room(owner, base, "Leaf", null);
        var product = item(owner, base, "RACE");
        var changes = race(
                () -> call(
                                owner,
                                put(base + "/items/" + product.get("id").asString() + "/placements/"
                                        + leaf.get("id").asString()),
                                null)
                        .status(),
                () -> call(
                                owner,
                                post(base + "/locations"),
                                Map.of(
                                        "name",
                                        "Child",
                                        "type",
                                        "BIN",
                                        "parentId",
                                        leaf.get("id").asString()))
                        .status());
        assertThat(changes.stream().filter(value -> value == 409).count()).isEqualTo(1);
        assertThat(changes.stream().filter(value -> value >= 200 && value < 300).count())
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
