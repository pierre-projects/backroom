package com.backroom;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 7: CSV import preview, apply, idempotency, limits, roles, isolation and an export → import round trip. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTests.PostgresConfiguration.class)
class ImportIntegrationTests extends ApiTestSupport {

    record Shop(Client owner, String base, UUID orgId, String stockroom, String coffeeShelf) {}

    Shop shop() throws Exception {
        var owner = register();
        var org = org(owner);
        var base = base(org);
        var stockroom = location(owner, base, "Stockroom", null).get("id").asString();
        var coffee = location(owner, base, "Coffee shelf", stockroom).get("id").asString();
        return new Shop(owner, base, UUID.fromString(org.get("id").asString()), stockroom, coffee);
    }
    MockHttpServletResponse preview(Client client, String base, String csv) throws Exception {
        return mvc.perform(post(base + "/imports/items/preview").session(client.session()).header("X-CSRF-TOKEN", client.csrf())
                .contentType("text/csv").content(csv.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse();
    }
    JsonNode previewOk(Client client, String base, String csv) throws Exception {
        var response = preview(client, base, csv);
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    Response apply(Client client, String base, UUID key, String csv, Integer... include) throws Exception {
        return call(client, post(base + "/imports/items"), Map.of("importKey", key.toString(), "fileName", "catalog.csv", "csv", csv, "include", List.of(include)));
    }
    Map<Integer, JsonNode> byLine(JsonNode preview) {
        var rows = new HashMap<Integer, JsonNode>();
        preview.get("rows").forEach(row -> rows.put(row.get("line").asInt(), row));
        return rows;
    }
    BigDecimal balance(UUID orgId, String sku, String location) {
        return jdbc.queryForObject("""
                select b.quantity from stock_balances b join items i on i.id = b.item_id join locations l on l.id = b.location_id
                where b.organization_id = ? and i.sku = ? and l.name = ?
                """, BigDecimal.class, orgId, sku, location).stripTrailingZeros();
    }

    static final String CATALOG = """
            Item,SKU,Unit,Location,Starting quantity,Minimum
            House espresso,COF-101,kg,Stockroom > Coffee shelf,12,4
            Oat milk,,L,Stockroom > Fridge,6,
            Decaf espresso,cof-102,Kilogram,stockroom >  coffee SHELF,3,1
            "Paper cups, 12 oz",CUP-12,each,Stockroom > Dry goods > Cups bin,240,100
            "Paper cups, 12 oz",CUP-12,each,Stockroom › Dry goods › Spare bin,"2,5",
            Syrup,,litres,Old room > Shelf,1,
            Napkins,,each,Stockroom,10,
            Lids,,each,,,5
            Beans,,pounds,,,
            Filters,FIL,each,Stockroom > Coffee shelf > Drawer,5,
            """;

    @Test
    void previewSortsEveryRowAndApplyCreatesWhatItShowed() throws Exception {
        var shop = shop();
        var existing = ok(shop.owner, post(shop.base + "/items"), Map.of("name", "House espresso", "sku", "COF-101", "unit", "KILOGRAM")).get("id").asString();
        ok(shop.owner, post(shop.base + "/inventory/receipts"), Map.of("operationKey", UUID.randomUUID().toString(), "itemId", existing,
                "locationId", shop.coffeeShelf, "quantity", "7"));
        item(shop.owner, shop.base, "Oat milk", "LITER");
        var old = location(shop.owner, shop.base, "Old room", null);
        ok(shop.owner, post(shop.base + "/locations/" + old.get("id").asString() + "/archive"), Map.of("expectedVersion", old.get("version").asLong()));

        var preview = previewOk(shop.owner, shop.base, CATALOG);
        var rows = byLine(preview);
        assertThat(rows.get(2).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(rows.get(3).get("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(rows.get(4).get("status").asString()).isEqualTo("NEW");
        assertThat(rows.get(4).get("location").asString()).isEqualTo("Stockroom › Coffee shelf");
        assertThat(rows.get(4).get("unit").asString()).isEqualTo("kg");
        assertThat(rows.get(5).get("status").asString()).isEqualTo("NEW");
        assertThat(rows.get(6).get("status").asString()).isEqualTo("ERROR");
        assertThat(rows.get(6).get("messages").get(0).asString()).contains("Starting quantity").contains("whole numbers");
        assertThat(rows.get(7).get("messages").get(0).asString()).contains("Old room").contains("archived");
        assertThat(rows.get(8).get("messages").get(0).asString()).contains("Stockroom").contains("has locations inside it");
        assertThat(rows.get(9).get("messages").get(0).asString()).isEqualTo("A minimum needs a Location.");
        assertThat(rows.get(10).get("messages").get(0).asString()).contains("pounds");
        assertThat(rows.get(11).get("messages").get(0).asString()).contains("Coffee shelf").contains("holds items");
        var summary = preview.get("summary");
        assertThat(summary.get("rows").asInt()).isEqualTo(10);
        assertThat(summary.get("newItems").asInt()).isEqualTo(2);
        assertThat(summary.get("duplicates").asInt()).isEqualTo(1);
        assertThat(summary.get("possibleDuplicates").asInt()).isEqualTo(1);
        assertThat(summary.get("errors").asInt()).isEqualTo(6);
        assertThat(summary.get("locationsToCreate")).extracting(JsonNode::asString)
                .containsExactly("Stockroom › Dry goods", "Stockroom › Dry goods › Cups bin");

        // Apply with the possible duplicate included: it becomes a second "Oat milk" with its own new shelf.
        var key = UUID.randomUUID();
        var applied = apply(shop.owner, shop.base, key, CATALOG, 3);
        assertThat(applied.status()).isEqualTo(201);
        assertThat(applied.json().get("itemsCreated").asInt()).isEqualTo(3);
        assertThat(applied.json().get("locationsCreated").asInt()).isEqualTo(3);
        assertThat(applied.json().get("placements").asInt()).isEqualTo(3);
        assertThat(applied.json().get("quantities").asInt()).isEqualTo(3);
        assertThat(applied.json().get("minimums").asInt()).isEqualTo(2);
        assertThat(applied.json().get("skipped").asInt()).isEqualTo(1);
        assertThat(applied.json().get("errors").asInt()).isEqualTo(6);

        assertThat(balance(shop.orgId, "COF-102", "Coffee shelf")).isEqualByComparingTo("3");
        assertThat(balance(shop.orgId, "CUP-12", "Cups bin")).isEqualByComparingTo("240");
        assertThat(balance(shop.orgId, "COF-101", "Coffee shelf")).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("select name from items where organization_id = ? and sku = 'COF-101'", String.class, shop.orgId)).isEqualTo("House espresso");
        assertThat(jdbc.queryForObject("select count(*) from items where organization_id = ? and lower(name) = 'oat milk'", Integer.class, shop.orgId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select p.minimum_quantity from item_placements p join items i on i.id = p.item_id join locations l on l.id = p.location_id
                where i.organization_id = ? and i.sku = 'CUP-12' and l.name = 'Cups bin'
                """, BigDecimal.class, shop.orgId)).isEqualByComparingTo("100");
        var cupsBin = jdbc.queryForMap("select l.type, p.name as parent, g.name as grandparent from locations l join locations p on p.id = l.parent_id join locations g on g.id = p.parent_id where l.organization_id = ? and l.name = 'Cups bin'", shop.orgId);
        assertThat(cupsBin).containsEntry("type", "SHELF").containsEntry("parent", "Dry goods").containsEntry("grandparent", "Stockroom");
        assertThat(jdbc.queryForObject("""
                select count(*) from stock_balances b where b.organization_id = ? and b.quantity <> coalesce((select sum(m.quantity_change)
                from stock_movements m where m.item_id = b.item_id and m.location_id = b.location_id), 0)
                """, Integer.class, shop.orgId)).isZero();
        assertThat(jdbc.queryForObject("select note from stock_operations o join items i on i.id = o.item_id where i.sku = 'CUP-12' and o.organization_id = ?",
                String.class, shop.orgId)).isEqualTo("Imported from catalog.csv");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where organization_id = ? and action = 'ITEM_IMPORT_COMPLETED'", Integer.class, shop.orgId)).isEqualTo(1);

        // A retry replays; the same key with another file is refused; a fresh preview sees what now exists.
        var replay = apply(shop.owner, shop.base, key, CATALOG, 3);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json().get("replayed").asBoolean()).isTrue();
        assertThat(replay.json().get("itemsCreated").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from items where organization_id = ?", Integer.class, shop.orgId)).isEqualTo(5);
        assertThat(apply(shop.owner, shop.base, key, CATALOG.replace("Decaf", "Decaff")).code()).isEqualTo("IMPORT_KEY_REUSED");
        var again = byLine(previewOk(shop.owner, shop.base, CATALOG));
        assertThat(again.get(4).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(again.get(5).get("status").asString()).isEqualTo("DUPLICATE");
    }

    @Test
    void importsAreLimitedRoleCheckedAndScopedToOneBusiness() throws Exception {
        var shop = shop();
        assertThat(preview(shop.owner, shop.base, "Name,Qty\nBeans,1\n").getContentAsString()).contains("IMPORT_HEADER");
        assertThat(preview(shop.owner, shop.base, "Item,Unit\n").getContentAsString()).contains("no rows");
        var big = new StringBuilder("Item,Unit\n");
        for (int index = 0; index < 2001; index++) big.append("Item ").append(index).append(",each\n");
        var tooMany = preview(shop.owner, shop.base, big.toString());
        assertThat(tooMany.getStatus()).isEqualTo(413);
        assertThat(tooMany.getContentAsString()).contains("IMPORT_TOO_LARGE");
        var huge = "Item,Unit\n" + "x".repeat(1024 * 1024) + ",each\n";
        assertThat(preview(shop.owner, shop.base, huge).getStatus()).isEqualTo(413);
        assertThat(apply(shop.owner, shop.base, UUID.randomUUID(), huge).status()).isEqualTo(413);

        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(preview(employee, shop.base, "Item,Unit\nBeans,kg\n").getStatus()).isEqualTo(403);
        assertThat(apply(employee, shop.base, UUID.randomUUID(), "Item,Unit\nBeans,kg\n").status()).isEqualTo(403);
        var template = mvc.perform(get(shop.base + "/imports/items/template.csv").session(employee.session())).andReturn().getResponse();
        assertThat(template.getContentAsString(StandardCharsets.UTF_8)).startsWith("﻿Item,SKU,Unit,Location,Starting quantity,Minimum");

        // Another business's SKUs and locations are invisible: the same file creates its own.
        ok(shop.owner, post(shop.base + "/items"), Map.of("name", "House espresso", "sku", "COF-101", "unit", "KILOGRAM"));
        var other = shop();
        var rows = byLine(previewOk(other.owner, other.base, "Item,SKU,Unit,Location,Starting quantity\nHouse espresso,COF-101,kg,Back room > Shelf,2\n"));
        assertThat(rows.get(2).get("status").asString()).isEqualTo("NEW");
        assertThat(apply(other.owner, other.base, UUID.randomUUID(), "Item,SKU,Unit,Location,Starting quantity\nHouse espresso,COF-101,kg,Back room > Shelf,2\n").status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from locations where organization_id = ? and name = 'Back room'", Integer.class, shop.orgId)).isZero();
        assertThat(call(other.owner, post(shop.base + "/imports/items"), Map.of("importKey", UUID.randomUUID().toString(), "csv", "Item,Unit\nA,kg\n")).status()).isEqualTo(404);
    }

    @Test
    void aStockExportImportsIntoAnotherBusiness() throws Exception {
        var source = shop();
        var csv = """
                Item,SKU,Unit,Location,Starting quantity,Minimum
                House espresso,COF-101,kg,Stockroom > Coffee shelf,12.5,4
                Oat milk,OAT-01,L,Coffee bar > Fridge,6,
                Oat milk,OAT-01,L,Stockroom > Coffee shelf,0,
                """;
        assertThat(apply(source.owner, source.base, UUID.randomUUID(), csv).status()).isEqualTo(201);
        var export = mvc.perform(get(source.base + "/exports/stock.csv").session(source.owner.session())).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        var target = shop();
        var rows = byLine(previewOk(target.owner, target.base, export));
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.get("status").asString()).isEqualTo("NEW"));
        assertThat(apply(target.owner, target.base, UUID.randomUUID(), export).status()).isEqualTo(201);
        assertThat(balance(target.orgId, "COF-101", "Coffee shelf")).isEqualByComparingTo("12.5");
        assertThat(balance(target.orgId, "OAT-01", "Fridge")).isEqualByComparingTo("6");
        assertThat(balance(target.orgId, "OAT-01", "Coffee shelf")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("""
                select p.minimum_quantity from item_placements p join items i on i.id = p.item_id where i.organization_id = ? and i.sku = 'COF-101'
                """, BigDecimal.class, target.orgId)).isEqualByComparingTo("4");
    }
}
