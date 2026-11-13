package com.backroom.exports;

import static org.assertj.core.api.Assertions.assertThat;
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

/** CSV exports: manager-only access, date ranges, row limits and audit. */
@IntegrationTest
class ExportIntegrationTests extends ApiTestSupport {
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

    record Download(int status, String body, String disposition, String code) {}

    Download export(Client client, String url) throws Exception {
        var result = mvc.perform(get(url).session(client.session())).andReturn();
        var response = result.getResponse();
        var body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var code =
                response.getContentType() != null && response.getContentType().contains("json")
                        ? mapper.readTree(body).path("errorCode").asString()
                        : "";
        return new Download(response.getStatus(), body, response.getHeader("Content-Disposition"), code);
    }

    @Test
    void exportsSpreadsheetsForManagersWithGuardsRangesAndAudit() throws Exception {
        var shop = shop("KILOGRAM");
        var cups = ok(
                        shop.owner,
                        post(shop.base + "/items"),
                        Map.of("name", "=cmd|' /C calc'!A0", "sku", "CUP, 12", "unit", "EACH"))
                .get("id")
                .asString();
        assertThat(receive(shop.owner, shop, shop.shelfA, "2.5").status()).isEqualTo(201);
        assertThat(minimum(shop.owner, shop, shop.shelfA, "4").status()).isEqualTo(204);
        assertThat(call(
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
                                        "0.75",
                                        "reason",
                                        "WASTE",
                                        "note",
                                        "Bag split"))
                        .status())
                .isEqualTo(201);
        ok(
                shop.owner,
                post(shop.base + "/requests"),
                Map.of("itemId", shop.item, "locationId", shop.shelfA, "quantity", "3", "note", "+ weekend"));

        var stock = export(shop.owner, shop.base + "/exports/stock.csv");
        assertThat(stock.status()).isEqualTo(200);
        assertThat(stock.disposition()).startsWith("attachment").contains("backroom-stock-");
        assertThat(stock.body()).startsWith("﻿Item,SKU,Location,Unit,On hand,Minimum,Low,Item ID,Location ID\r\n");
        assertThat(stock.body())
                .contains(
                        "Espresso beans,,Stockroom › Shelf A,kg,1.75,4,Yes," + shop.item + "," + shop.shelfA + "\r\n");

        var items = export(shop.owner, shop.base + "/exports/items.csv").body();
        assertThat(items).contains("'=cmd|' /C calc'!A0,\"CUP, 12\",each,0,Active," + cups);

        var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        var movements =
                export(shop.owner, shop.base + "/exports/movements.csv?from=" + today.minusDays(1) + "&to=" + today);
        assertThat(movements.disposition())
                .contains("backroom-movements-" + today.minusDays(1) + "-to-" + today + ".csv");
        var lines = movements.body().split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains(",Received,Espresso beans,,Stockroom › Shelf A,kg,2.5,2.5,Test Person,,,No,");
        assertThat(lines[2])
                .contains(",Wasted,Espresso beans,,Stockroom › Shelf A,kg,-0.75,1.75,Test Person,,Bag split,No,");
        assertThat(export(shop.owner, shop.base + "/exports/movements.csv?from=2020-01-01&to=2020-01-31")
                        .body()
                        .split("\r\n"))
                .hasSize(1);
        // The business-wide history (used by Home's setup checklist) needs no item or location.
        assertThat(ok(shop.owner, get(shop.base + "/inventory/movements?size=1"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(2);

        var requests = export(shop.owner, shop.base + "/exports/requests.csv").body();
        assertThat(requests)
                .contains("R-1,Submitted,Espresso beans,,Stockroom › Shelf A,kg,3,,0,Test Person,")
                .contains(",'+ weekend,");

        var backwards =
                export(shop.owner, shop.base + "/exports/movements.csv?from=" + today + "&to=" + today.minusDays(3));
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(export(shop.owner, shop.base + "/exports/secrets.csv").status())
                .isEqualTo(404);

        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(export(employee, shop.base + "/exports/stock.csv").status()).isEqualTo(403);
        var manager = register();
        join(manager, invitation(shop.owner, shop.base, "MANAGER"));
        assertThat(export(manager, shop.base + "/exports/stock.csv").status()).isEqualTo(200);
        var other = shop("EACH");
        assertThat(export(other.owner, shop.base + "/exports/stock.csv").status())
                .isEqualTo(404);
        assertThat(export(other.owner, other.base + "/exports/stock.csv").body())
                .doesNotContain(shop.item);

        var audits = jdbc.queryForList(
                "select metadata::text from audit_events where action='DATA_EXPORTED' and organization_id=? order by created_at, id",
                String.class,
                UUID.fromString(shop.base.substring(shop.base.lastIndexOf('/') + 1)));
        assertThat(audits).hasSize(6);
        assertThat(audits.get(2))
                .contains("\"export\": \"movements\"")
                .contains("\"rows\": \"2\"")
                .contains("\"from\": \"" + today.minusDays(1) + "\"");
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
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action='DATA_EXPORTED' and organization_id=?",
                        Integer.class,
                        orgId))
                .isZero();
        assertThat(export(shop.owner, shop.base + "/exports/stock.csv").status())
                .isEqualTo(200);
    }
}
