package com.backroom.search;

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

/** One search across items, locations and codes, within one business. */
@IntegrationTest
class SearchIntegrationTests extends ApiTestSupport {
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

    @Test
    void searchFindsItemsLocationsAndCodesWithinOneBusiness() throws Exception {
        var shop = shop("KILOGRAM");
        var milk = ok(
                        shop.owner,
                        post(shop.base + "/items"),
                        Map.of("name", "Oat milk", "sku", "OAT-01", "unit", "LITER"))
                .get("id")
                .asString();
        var old = ok(shop.owner, post(shop.base + "/items"), Map.of("name", "Oat syrup", "unit", "LITER"));
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/items/" + old.get("id").asString() + "/archive"),
                                Map.of("expectedVersion", old.get("version").asLong()))
                        .status())
                .isEqualTo(204);
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));

        var byName = ok(employee, get(shop.base + "/search").param("q", "  OAT "), null);
        assertThat(ids(byName, "items", "id")).containsExactly(milk);
        assertThat(byName.get("code").isNull()).isTrue();
        assertThat(ids(ok(employee, get(shop.base + "/search").param("q", "oat-01"), null), "items", "id"))
                .containsExactly(milk);

        var shelves = ok(employee, get(shop.base + "/search").param("q", "shelf"), null);
        assertThat(shelves.get("locations")).hasSize(2);
        assertThat(shelves.get("locations").get(0).get("parentName").asString()).isEqualTo("Stockroom");
        assertThat(shelves.get("items")).isEmpty();

        // A pasted label link or a registered barcode resolves to exactly one thing.
        var qr = ok(shop.owner, post(shop.base + "/codes"), Map.of("type", "LOCATION", "id", shop.shelfA));
        var byLink = ok(
                        employee,
                        get(shop.base + "/search").param("q", qr.get("url").asString()),
                        null)
                .get("code");
        assertThat(byLink.get("type").asString()).isEqualTo("LOCATION");
        assertThat(byLink.get("id").asString()).isEqualTo(shop.shelfA);
        assertThat(ok(
                                employee,
                                get(shop.base + "/search")
                                        .param("q", qr.get("display").asString()),
                                null)
                        .get("code")
                        .get("id")
                        .asString())
                .isEqualTo(shop.shelfA);
        ok(shop.owner, post(shop.base + "/items/" + milk + "/barcodes"), Map.of("value", "036000291452"));
        var byBarcode = ok(employee, get(shop.base + "/search").param("q", "036000291452"), null)
                .get("code");
        assertThat(byBarcode.get("type").asString()).isEqualTo("ITEM");
        assertThat(byBarcode.get("name").asString()).isEqualTo("Oat milk");

        var empty = ok(employee, get(shop.base + "/search").param("q", "   "), null);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("locations")).isEmpty();

        // Another business sees none of it, even with the exact codes.
        var other = shop("EACH");
        assertThat(ok(other.owner, get(other.base + "/search").param("q", "oat"), null)
                        .get("items"))
                .isEmpty();
        assertThat(ok(
                                other.owner,
                                get(other.base + "/search")
                                        .param("q", qr.get("url").asString()),
                                null)
                        .get("code")
                        .isNull())
                .isTrue();
        assertThat(ok(other.owner, get(other.base + "/search").param("q", "036000291452"), null)
                        .get("code")
                        .isNull())
                .isTrue();
        assertThat(call(other.owner, get(shop.base + "/search").param("q", "oat"), null)
                        .status())
                .isEqualTo(404);
    }
}
