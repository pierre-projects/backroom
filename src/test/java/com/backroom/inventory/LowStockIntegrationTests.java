package com.backroom.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Low-stock minimums: validation, roles, isolation and the low-stock list. */
@IntegrationTest
class LowStockIntegrationTests extends ApiTestSupport {
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

    Response receive(Client client, Shop shop, String location, String quantity) throws Exception {
        return call(
                client,
                post(shop.base + "/inventory/receipts"),
                Map.of(
                        "operationKey",
                        UUID.randomUUID().toString(),
                        "itemId",
                        shop.item,
                        "locationId",
                        location,
                        "quantity",
                        quantity));
    }

    Response minimum(Client client, Shop shop, String location, String value) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("minimum", value);
        return call(client, put(shop.base + "/items/" + shop.item + "/placements/" + location + "/minimum"), body);
    }

    JsonNode slot(Shop shop, String location) throws Exception {
        for (var balance : ok(shop.owner, get(shop.base + "/inventory/balances?itemId=" + shop.item), null)) {
            if (balance.get("locationId").asString().equals(location)) return balance;
        }
        throw new AssertionError("No balance for " + location);
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

    @Test
    void minimumsMarkLowSlotsAndFeedTheLowStockList() throws Exception {
        var shop = shop("KILOGRAM");
        assertThat(receive(shop.owner, shop, shop.shelfA, "3").status()).isEqualTo(201);
        assertThat(receive(shop.owner, shop, shop.shelfB, "10").status()).isEqualTo(201);
        var other = item(shop.owner, shop.base, "Oat milk", "LITER").get("id").asString();

        assertThat(minimum(shop.owner, shop, shop.shelfA, "4.5").status()).isEqualTo(204);
        assertThat(minimum(shop.owner, shop, shop.shelfB, "2").status()).isEqualTo(204);
        assertThat(slot(shop, shop.shelfA).get("minimum").asString()).isEqualTo("4.5");
        assertThat(slot(shop, shop.shelfA).get("low").asBoolean()).isTrue();
        assertThat(slot(shop, shop.shelfB).get("low").asBoolean()).isFalse();
        assertThat(ok(shop.owner, get(shop.base + "/items/" + shop.item), null)
                        .get("low")
                        .asBoolean())
                .isTrue();

        var low = ok(shop.owner, get(shop.base + "/inventory/low-stock"), null);
        assertThat(low.get("totalElements").asInt()).isEqualTo(1);
        var row = low.get("items").get(0);
        assertThat(row.get("itemName").asString()).isEqualTo("Espresso beans");
        assertThat(row.get("locationName").asString()).isEqualTo("Shelf A");
        assertThat(row.get("parentName").asString()).isEqualTo("Stockroom");
        assertThat(row.get("quantity").asString()).isEqualTo("3");
        assertThat(row.get("minimum").asString()).isEqualTo("4.5");
        assertThat(row.get("shortfall").asString()).isEqualTo("1.5");
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock?locationId=" + shop.shelfB), null)
                        .get("totalElements")
                        .asInt())
                .isZero();

        // The items list can filter to low stock and to one location.
        assertThat(ids(ok(shop.owner, get(shop.base + "/items?lowStock=true"), null), "id"))
                .containsExactly(shop.item);
        var all = ok(shop.owner, get(shop.base + "/items"), null);
        all.get("items")
                .forEach(item -> assertThat(item.get("low").asBoolean())
                        .isEqualTo(item.get("id").asString().equals(shop.item)));
        assertThat(ids(all, "id")).contains(other);
        assertThat(ids(ok(shop.owner, get(shop.base + "/items?locationId=" + shop.shelfB), null), "id"))
                .containsExactly(shop.item);

        // Receiving up to the minimum clears the warning; exactly at the minimum is not low.
        assertThat(receive(shop.owner, shop, shop.shelfA, "1.5").status()).isEqualTo(201);
        assertThat(slot(shop, shop.shelfA).get("low").asBoolean()).isFalse();
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();

        // A newly placed, empty slot with a minimum is low straight away.
        var shelfC =
                location(shop.owner, shop.base, "Shelf C", shop.room).get("id").asString();
        ok(shop.owner, put(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(minimum(shop.owner, shop, shelfC, "1").status()).isEqualTo(204);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null)
                        .get("items")
                        .get(0)
                        .get("shortfall")
                        .asString())
                .isEqualTo("1");

        // Clearing the minimum, or removing the location, ends the warning.
        assertThat(minimum(shop.owner, shop, shelfC, null).status()).isEqualTo(204);
        assertThat(slot(shop, shelfC).get("minimum").isNull()).isTrue();
        assertThat(minimum(shop.owner, shop, shelfC, "2").status()).isEqualTo(204);
        ok(shop.owner, delete(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();
        ok(shop.owner, put(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(slot(shop, shelfC).get("minimum").isNull()).isTrue();

        var events = jdbc.queryForList(
                "select metadata::text from audit_events where action='MINIMUM_SET' and resource_id=? order by created_at, id",
                String.class,
                UUID.fromString(shop.item));
        assertThat(events).hasSize(5);
        assertThat(events.getFirst()).contains("\"minimum\": \"4.5\"").contains("Shelf A");
    }

    @Test
    void minimumsAreValidatedRoleCheckedAndIsolated() throws Exception {
        var shop = shop("EACH");
        assertThat(receive(shop.owner, shop, shop.shelfA, "1").status()).isEqualTo(201);
        assertThat(minimum(shop.owner, shop, shop.shelfA, "1.5").status()).isEqualTo(400);
        assertThat(minimum(shop.owner, shop, shop.shelfA, "0").status()).isEqualTo(400);
        assertThat(minimum(shop.owner, shop, shop.shelfA, "-2").status()).isEqualTo(400);
        var notPlaced = minimum(shop.owner, shop, shop.shelfB, "2");
        assertThat(notPlaced.status()).isEqualTo(409);
        assertThat(notPlaced.code()).isEqualTo("NOT_PLACED");

        var manager = register();
        join(manager, invitation(shop.owner, shop.base, "MANAGER"));
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(minimum(manager, shop, shop.shelfA, "3").status()).isEqualTo(204);
        assertThat(minimum(employee, shop, shop.shelfA, "5").status()).isEqualTo(403);
        assertThat(call(employee, get(shop.base + "/inventory/low-stock"), null)
                        .json()
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);

        var other = shop("EACH");
        assertThat(minimum(
                                other.owner,
                                new Shop(other.owner, other.base, shop.item, other.room, shop.shelfA, other.shelfB),
                                shop.shelfA,
                                "2")
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(other.base + "/inventory/low-stock?locationId=" + shop.shelfA), null)
                        .status())
                .isEqualTo(404);
        assertThat(ok(other.owner, get(other.base + "/inventory/low-stock"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();
        assertThat(call(other.owner, get(other.base + "/items?locationId=" + shop.shelfA), null)
                        .status())
                .isEqualTo(404);
        assertThat(ok(other.owner, get(other.base + "/items?lowStock=true"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();

        // Archived items drop out of the low-stock list.
        var used = call(
                shop.owner,
                post(shop.base + "/inventory/removals"),
                Map.of(
                        "operationKey",
                        UUID.randomUUID().toString(),
                        "itemId",
                        shop.item,
                        "locationId",
                        shop.shelfA,
                        "quantity",
                        "1",
                        "reason",
                        "USAGE"));
        assertThat(used.status()).isEqualTo(201);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        var version = ok(shop.owner, get(shop.base + "/items/" + shop.item), null)
                .get("version")
                .asLong();
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/items/" + shop.item + "/archive"),
                                Map.of("expectedVersion", version))
                        .status())
                .isEqualTo(204);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null)
                        .get("totalElements")
                        .asInt())
                .isZero();
    }
}
