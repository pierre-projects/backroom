package com.backroom;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 5: low-stock minimums, search, exports and account recovery. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTests.PostgresConfiguration.class)
class UsabilityIntegrationTests extends ApiTestSupport {

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
        return call(client, post(shop.base + "/inventory/receipts"), Map.of("operationKey", UUID.randomUUID().toString(),
                "itemId", shop.item, "locationId", location, "quantity", quantity));
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
        assertThat(ok(shop.owner, get(shop.base + "/items/" + shop.item), null).get("low").asBoolean()).isTrue();

        var low = ok(shop.owner, get(shop.base + "/inventory/low-stock"), null);
        assertThat(low.get("totalElements").asInt()).isEqualTo(1);
        var row = low.get("items").get(0);
        assertThat(row.get("itemName").asString()).isEqualTo("Espresso beans");
        assertThat(row.get("locationName").asString()).isEqualTo("Shelf A");
        assertThat(row.get("parentName").asString()).isEqualTo("Stockroom");
        assertThat(row.get("quantity").asString()).isEqualTo("3");
        assertThat(row.get("minimum").asString()).isEqualTo("4.5");
        assertThat(row.get("shortfall").asString()).isEqualTo("1.5");
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock?locationId=" + shop.shelfB), null).get("totalElements").asInt()).isZero();

        // The items list can filter to low stock and to one location.
        assertThat(ids(ok(shop.owner, get(shop.base + "/items?lowStock=true"), null), "id")).containsExactly(shop.item);
        var all = ok(shop.owner, get(shop.base + "/items"), null);
        all.get("items").forEach(item -> assertThat(item.get("low").asBoolean()).isEqualTo(item.get("id").asString().equals(shop.item)));
        assertThat(ids(all, "id")).contains(other);
        assertThat(ids(ok(shop.owner, get(shop.base + "/items?locationId=" + shop.shelfB), null), "id")).containsExactly(shop.item);

        // Receiving up to the minimum clears the warning; exactly at the minimum is not low.
        assertThat(receive(shop.owner, shop, shop.shelfA, "1.5").status()).isEqualTo(201);
        assertThat(slot(shop, shop.shelfA).get("low").asBoolean()).isFalse();
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null).get("totalElements").asInt()).isZero();

        // A newly placed, empty slot with a minimum is low straight away.
        var shelfC = location(shop.owner, shop.base, "Shelf C", shop.room).get("id").asString();
        ok(shop.owner, put(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(minimum(shop.owner, shop, shelfC, "1").status()).isEqualTo(204);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null).get("items").get(0).get("shortfall").asString()).isEqualTo("1");

        // Clearing the minimum, or removing the location, ends the warning.
        assertThat(minimum(shop.owner, shop, shelfC, null).status()).isEqualTo(204);
        assertThat(slot(shop, shelfC).get("minimum").isNull()).isTrue();
        assertThat(minimum(shop.owner, shop, shelfC, "2").status()).isEqualTo(204);
        ok(shop.owner, delete(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null).get("totalElements").asInt()).isZero();
        ok(shop.owner, put(shop.base + "/items/" + shop.item + "/placements/" + shelfC), null);
        assertThat(slot(shop, shelfC).get("minimum").isNull()).isTrue();

        var events = jdbc.queryForList("select metadata::text from audit_events where action='MINIMUM_SET' and resource_id=? order by created_at, id", String.class, UUID.fromString(shop.item));
        assertThat(events).hasSize(5);
        assertThat(events.getFirst()).contains("\"minimum\": \"4.5\"").contains("Shelf A");
    }

    @Test
    void searchFindsItemsLocationsAndCodesWithinOneBusiness() throws Exception {
        var shop = shop("KILOGRAM");
        var milk = ok(shop.owner, post(shop.base + "/items"), Map.of("name", "Oat milk", "sku", "OAT-01", "unit", "LITER")).get("id").asString();
        var old = ok(shop.owner, post(shop.base + "/items"), Map.of("name", "Oat syrup", "unit", "LITER"));
        assertThat(call(shop.owner, post(shop.base + "/items/" + old.get("id").asString() + "/archive"), Map.of("expectedVersion", old.get("version").asLong())).status()).isEqualTo(204);
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));

        var byName = ok(employee, get(shop.base + "/search").param("q", "  OAT "), null);
        assertThat(ids(byName, "items", "id")).containsExactly(milk);
        assertThat(byName.get("code").isNull()).isTrue();
        assertThat(ids(ok(employee, get(shop.base + "/search").param("q", "oat-01"), null), "items", "id")).containsExactly(milk);

        var shelves = ok(employee, get(shop.base + "/search").param("q", "shelf"), null);
        assertThat(shelves.get("locations")).hasSize(2);
        assertThat(shelves.get("locations").get(0).get("parentName").asString()).isEqualTo("Stockroom");
        assertThat(shelves.get("items")).isEmpty();

        // A pasted label link or a registered barcode resolves to exactly one thing.
        var qr = ok(shop.owner, post(shop.base + "/codes"), Map.of("type", "LOCATION", "id", shop.shelfA));
        var byLink = ok(employee, get(shop.base + "/search").param("q", qr.get("url").asString()), null).get("code");
        assertThat(byLink.get("type").asString()).isEqualTo("LOCATION");
        assertThat(byLink.get("id").asString()).isEqualTo(shop.shelfA);
        assertThat(ok(employee, get(shop.base + "/search").param("q", qr.get("display").asString()), null).get("code").get("id").asString()).isEqualTo(shop.shelfA);
        ok(shop.owner, post(shop.base + "/items/" + milk + "/barcodes"), Map.of("value", "036000291452"));
        var byBarcode = ok(employee, get(shop.base + "/search").param("q", "036000291452"), null).get("code");
        assertThat(byBarcode.get("type").asString()).isEqualTo("ITEM");
        assertThat(byBarcode.get("name").asString()).isEqualTo("Oat milk");

        var empty = ok(employee, get(shop.base + "/search").param("q", "   "), null);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("locations")).isEmpty();

        // Another business sees none of it, even with the exact codes.
        var other = shop("EACH");
        assertThat(ok(other.owner, get(other.base + "/search").param("q", "oat"), null).get("items")).isEmpty();
        assertThat(ok(other.owner, get(other.base + "/search").param("q", qr.get("url").asString()), null).get("code").isNull()).isTrue();
        assertThat(ok(other.owner, get(other.base + "/search").param("q", "036000291452"), null).get("code").isNull()).isTrue();
        assertThat(call(other.owner, get(shop.base + "/search").param("q", "oat"), null).status()).isEqualTo(404);
    }

    record Download(int status, String body, String disposition, String code) {}
    Download export(Client client, String url) throws Exception {
        var result = mvc.perform(get(url).session(client.session())).andReturn();
        var response = result.getResponse();
        var body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var code = response.getContentType() != null && response.getContentType().contains("json") ? mapper.readTree(body).path("errorCode").asString() : "";
        return new Download(response.getStatus(), body, response.getHeader("Content-Disposition"), code);
    }

    @Test
    void exportsSpreadsheetsForManagersWithGuardsRangesAndAudit() throws Exception {
        var shop = shop("KILOGRAM");
        var cups = ok(shop.owner, post(shop.base + "/items"), Map.of("name", "=cmd|' /C calc'!A0", "sku", "CUP, 12", "unit", "EACH")).get("id").asString();
        assertThat(receive(shop.owner, shop, shop.shelfA, "2.5").status()).isEqualTo(201);
        assertThat(minimum(shop.owner, shop, shop.shelfA, "4").status()).isEqualTo(204);
        assertThat(call(shop.owner, post(shop.base + "/inventory/removals"), Map.of("operationKey", UUID.randomUUID().toString(),
                "itemId", shop.item, "locationId", shop.shelfA, "quantity", "0.75", "reason", "WASTE", "note", "Bag split")).status()).isEqualTo(201);
        ok(shop.owner, post(shop.base + "/requests"), Map.of("itemId", shop.item, "locationId", shop.shelfA, "quantity", "3", "note", "+ weekend"));

        var stock = export(shop.owner, shop.base + "/exports/stock.csv");
        assertThat(stock.status()).isEqualTo(200);
        assertThat(stock.disposition()).startsWith("attachment").contains("backroom-stock-");
        assertThat(stock.body()).startsWith("﻿Item,SKU,Location,Unit,On hand,Minimum,Low,Item ID,Location ID\r\n");
        assertThat(stock.body()).contains("Espresso beans,,Stockroom › Shelf A,kg,1.75,4,Yes," + shop.item + "," + shop.shelfA + "\r\n");

        var items = export(shop.owner, shop.base + "/exports/items.csv").body();
        assertThat(items).contains("'=cmd|' /C calc'!A0,\"CUP, 12\",each,0,Active," + cups);

        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        var movements = export(shop.owner, shop.base + "/exports/movements.csv?from=" + today.minusDays(1) + "&to=" + today);
        assertThat(movements.disposition()).contains("backroom-movements-" + today.minusDays(1) + "-to-" + today + ".csv");
        var lines = movements.body().split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains(",Received,Espresso beans,,Stockroom › Shelf A,kg,2.5,2.5,Test Person,,,No,");
        assertThat(lines[2]).contains(",Wasted,Espresso beans,,Stockroom › Shelf A,kg,-0.75,1.75,Test Person,,Bag split,No,");
        assertThat(export(shop.owner, shop.base + "/exports/movements.csv?from=2020-01-01&to=2020-01-31").body().split("\r\n")).hasSize(1);
        // The business-wide history (used by Home's setup checklist) needs no item or location.
        assertThat(ok(shop.owner, get(shop.base + "/inventory/movements?size=1"), null).get("totalElements").asInt()).isEqualTo(2);

        var requests = export(shop.owner, shop.base + "/exports/requests.csv").body();
        assertThat(requests).contains("R-1,Submitted,Espresso beans,,Stockroom › Shelf A,kg,3,,0,Test Person,").contains(",'+ weekend,");

        var backwards = export(shop.owner, shop.base + "/exports/movements.csv?from=" + today + "&to=" + today.minusDays(3));
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(export(shop.owner, shop.base + "/exports/secrets.csv").status()).isEqualTo(404);

        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(export(employee, shop.base + "/exports/stock.csv").status()).isEqualTo(403);
        var manager = register();
        join(manager, invitation(shop.owner, shop.base, "MANAGER"));
        assertThat(export(manager, shop.base + "/exports/stock.csv").status()).isEqualTo(200);
        var other = shop("EACH");
        assertThat(export(other.owner, shop.base + "/exports/stock.csv").status()).isEqualTo(404);
        assertThat(export(other.owner, other.base + "/exports/stock.csv").body()).doesNotContain(shop.item);

        var audits = jdbc.queryForList("select metadata::text from audit_events where action='DATA_EXPORTED' and organization_id=? order by created_at, id",
                String.class, UUID.fromString(shop.base.substring(shop.base.lastIndexOf('/') + 1)));
        assertThat(audits).hasSize(6);
        assertThat(audits.get(2)).contains("\"export\": \"movements\"").contains("\"rows\": \"2\"").contains("\"from\": \"" + today.minusDays(1) + "\"");
    }

    @Test
    void refusesExportsOverTheRowLimit() throws Exception {
        var shop = shop("EACH");
        var orgId = UUID.fromString(shop.base.substring(shop.base.lastIndexOf('/') + 1));
        // Bulk-load items directly; the limit is about rows, not how they were made.
        jdbc.update("""
                insert into items (id, version, created_at, organization_id, name, sku, unit, archived)
                select gen_random_uuid(), 0, now(), ?, 'Bulk item ' || n, null, 'EACH', false from generate_series(1, 50000) n
                """, orgId);
        var refused = export(shop.owner, shop.base + "/exports/items.csv");
        assertThat(refused.status()).isEqualTo(413);
        assertThat(refused.code()).isEqualTo("EXPORT_TOO_LARGE");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action='DATA_EXPORTED' and organization_id=?", Integer.class, orgId)).isZero();
        assertThat(export(shop.owner, shop.base + "/exports/stock.csv").status()).isEqualTo(200);
    }

    /** A browser with a session and CSRF token but nobody signed in, as when someone opens a reset link. */
    Client anonymous() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        return new Client(null, (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false), tree(result).get("token").asString(), null);
    }
    int login(String email, String password) throws Exception {
        var visitor = anonymous();
        return mvc.perform(post("/api/v1/auth/login").session(visitor.session()).header("X-CSRF-TOKEN", visitor.csrf())
                .param("email", email).param("password", password)).andReturn().getResponse().getStatus();
    }
    String token(JsonNode issued) { return issued.get("resetPath").asString().split("token=")[1]; }

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
        assertThat(call(visitor, post("/api/v1/auth/password-reset"), Map.of("token", token, "newPassword", "short")).status()).isEqualTo(400);
        var newPassword = "A brand new passphrase 77";
        var done = ok(visitor, post("/api/v1/auth/password-reset"), Map.of("token", token, "newPassword", newPassword));
        assertThat(done.get("email").asString()).isEqualTo(employee.email());

        // The employee's existing session ends; only the new password signs in; the link works once.
        var ended = call(employee, get(shop.base + "/items"), null);
        assertThat(ended.status()).isEqualTo(401);
        assertThat(ended.code()).isEqualTo("SESSION_ENDED");
        assertThat(login(employee.email(), PASSWORD)).isEqualTo(401);
        assertThat(login(employee.email(), newPassword)).isEqualTo(200);
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset"), Map.of("token", token, "newPassword", "Another passphrase 1234")).code()).isEqualTo("RESET_UNAVAILABLE");
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", token)).code()).isEqualTo("RESET_UNAVAILABLE");
        // Other people's sessions are untouched.
        assertThat(call(shop.owner, get(shop.base + "/items"), null).status()).isEqualTo(200);

        // Only the token's hash is stored, and neither the token nor the password reaches the audit log.
        assertThat(jdbc.queryForObject("select count(*) from password_resets where token_hash = ?", Integer.class, token)).isZero();
        var audit = jdbc.queryForList("select action || ' ' || metadata::text from audit_events where resource_id = ? and action like 'PASSWORD_RESET_%' order by created_at",
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
        assertThat(call(manager, post(shop.base + "/members/" + employee.id() + "/password-reset"), null).status()).isEqualTo(403);
        assertThat(call(employee, post(shop.base + "/members/" + manager.id() + "/password-reset"), null).status()).isEqualTo(403);
        var stranger = register();
        assertThat(call(shop.owner, post(shop.base + "/members/" + stranger.id() + "/password-reset"), null).status()).isEqualTo(404);

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
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", first)).code()).isEqualTo("RESET_UNAVAILABLE");
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", second)).status()).isEqualTo(200);
        jdbc.update("update password_resets set created_at = now() - interval '2 days', expires_at = now() - interval '1 day' where user_id = ? and revoked_at is null", manager.id());
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset"), Map.of("token", second, "newPassword", "Expired link passphrase")).code()).isEqualTo("RESET_UNAVAILABLE");
        assertThat(login(manager.email(), PASSWORD)).isEqualTo(200);
        assertThat(call(anonymous(), post("/api/v1/auth/password-reset/preview"), Map.of("token", "not-a-token")).status()).isEqualTo(400);
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
        assertThat(call(employee, get(shop.base + "/inventory/low-stock"), null).json().get("totalElements").asInt()).isEqualTo(1);

        var other = shop("EACH");
        assertThat(minimum(other.owner, new Shop(other.owner, other.base, shop.item, other.room, shop.shelfA, other.shelfB), shop.shelfA, "2").status()).isEqualTo(404);
        assertThat(call(other.owner, get(other.base + "/inventory/low-stock?locationId=" + shop.shelfA), null).status()).isEqualTo(404);
        assertThat(ok(other.owner, get(other.base + "/inventory/low-stock"), null).get("totalElements").asInt()).isZero();
        assertThat(call(other.owner, get(other.base + "/items?locationId=" + shop.shelfA), null).status()).isEqualTo(404);
        assertThat(ok(other.owner, get(other.base + "/items?lowStock=true"), null).get("totalElements").asInt()).isZero();

        // Archived items drop out of the low-stock list.
        var used = call(shop.owner, post(shop.base + "/inventory/removals"), Map.of("operationKey", UUID.randomUUID().toString(),
                "itemId", shop.item, "locationId", shop.shelfA, "quantity", "1", "reason", "USAGE"));
        assertThat(used.status()).isEqualTo(201);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null).get("totalElements").asInt()).isEqualTo(1);
        var version = ok(shop.owner, get(shop.base + "/items/" + shop.item), null).get("version").asLong();
        assertThat(call(shop.owner, post(shop.base + "/items/" + shop.item + "/archive"), Map.of("expectedVersion", version)).status()).isEqualTo(204);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/low-stock"), null).get("totalElements").asInt()).isZero();
    }
}
