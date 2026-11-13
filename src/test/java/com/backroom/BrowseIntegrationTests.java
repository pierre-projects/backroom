package com.backroom;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 6: location trails and contents counts, the Sold removal reason, and item photos. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTests.PostgresConfiguration.class)
class BrowseIntegrationTests extends ApiTestSupport {

    record Shop(Client owner, String base, String room, String shelf, String bin) {}

    Shop shop() throws Exception {
        var owner = register();
        var base = base(org(owner));
        var room = location(owner, base, "Stockroom", null).get("id").asString();
        var shelf = location(owner, base, "Shelf A", room).get("id").asString();
        var bin = ok(owner, post(base + "/locations"), Map.of("name", "Bin 3", "type", "BIN", "parentId", shelf)).get("id").asString();
        return new Shop(owner, base, room, shelf, bin);
    }
    String receive(Shop shop, String item, String location, String quantity) throws Exception {
        return ok(shop.owner, post(shop.base + "/inventory/receipts"), Map.of("operationKey", UUID.randomUUID().toString(),
                "itemId", item, "locationId", location, "quantity", quantity)).get("id").asString();
    }
    JsonNode find(JsonNode page, String id) {
        for (var row : page.get("items")) if (row.get("id").asString().equals(id)) return row;
        throw new AssertionError("Missing " + id);
    }

    @Test
    void locationsCarryTheirTrailAndContentsCounts() throws Exception {
        var shop = shop();
        var bin = ok(shop.owner, get(shop.base + "/locations/" + shop.bin), null);
        var trail = bin.get("ancestors");
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).get("name").asString()).isEqualTo("Stockroom");
        assertThat(trail.get(0).get("type").asString()).isEqualTo("ROOM");
        assertThat(trail.get(1).get("id").asString()).isEqualTo(shop.shelf);
        assertThat(ok(shop.owner, get(shop.base + "/locations/" + shop.room), null).get("ancestors")).isEmpty();

        // Counts: active children and unarchived, actively placed items only.
        var cups = item(shop.owner, shop.base, "Paper cups", "EACH").get("id").asString();
        var lids = item(shop.owner, shop.base, "Lids", "EACH").get("id").asString();
        var old = item(shop.owner, shop.base, "Old cups", "EACH");
        receive(shop, cups, shop.bin, "5");
        ok(shop.owner, put(shop.base + "/items/" + lids + "/placements/" + shop.bin), null);
        ok(shop.owner, put(shop.base + "/items/" + old.get("id").asString() + "/placements/" + shop.bin), null);
        ok(shop.owner, post(shop.base + "/items/" + old.get("id").asString() + "/archive"), Map.of("expectedVersion", old.get("version").asLong()));
        location(shop.owner, shop.base, "Shelf B", shop.room);
        var archivedShelf = location(shop.owner, shop.base, "Shelf C", shop.room);
        ok(shop.owner, post(shop.base + "/locations/" + archivedShelf.get("id").asString() + "/archive"),
                Map.of("expectedVersion", archivedShelf.get("version").asLong()));

        var roots = ok(shop.owner, get(shop.base + "/locations?rootsOnly=true"), null);
        assertThat(find(roots, shop.room).get("childCount").asLong()).isEqualTo(2);
        assertThat(find(roots, shop.room).get("itemCount").asLong()).isZero();
        var shelves = ok(shop.owner, get(shop.base + "/locations?parentId=" + shop.shelf), null);
        assertThat(find(shelves, shop.bin).get("itemCount").asLong()).isEqualTo(2);
        assertThat(find(shelves, shop.bin).get("childCount").asLong()).isZero();
        ok(shop.owner, delete(shop.base + "/items/" + lids + "/placements/" + shop.bin), null);
        assertThat(find(ok(shop.owner, get(shop.base + "/locations?parentId=" + shop.shelf), null), shop.bin).get("itemCount").asLong()).isEqualTo(1);

        // Another business can't read the trail.
        var other = shop();
        assertThat(call(other.owner, get(other.base + "/locations/" + shop.bin), null).status()).isEqualTo(404);
        assertThat(call(other.owner, get(shop.base + "/locations/" + shop.bin), null).status()).isEqualTo(404);
    }
}
