package com.backroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

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
        var bin = ok(owner, post(base + "/locations"), Map.of("name", "Bin 3", "type", "BIN", "parentId", shelf))
                .get("id")
                .asString();
        return new Shop(owner, base, room, shelf, bin);
    }

    String receive(Shop shop, String item, String location, String quantity) throws Exception {
        return ok(
                        shop.owner,
                        post(shop.base + "/inventory/receipts"),
                        Map.of(
                                "operationKey",
                                UUID.randomUUID().toString(),
                                "itemId",
                                item,
                                "locationId",
                                location,
                                "quantity",
                                quantity))
                .get("id")
                .asString();
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
        assertThat(ok(shop.owner, get(shop.base + "/locations/" + shop.room), null)
                        .get("ancestors"))
                .isEmpty();

        // Counts: active children and unarchived, actively placed items only.
        var cups = item(shop.owner, shop.base, "Paper cups", "EACH").get("id").asString();
        var lids = item(shop.owner, shop.base, "Lids", "EACH").get("id").asString();
        var old = item(shop.owner, shop.base, "Old cups", "EACH");
        receive(shop, cups, shop.bin, "5");
        ok(shop.owner, put(shop.base + "/items/" + lids + "/placements/" + shop.bin), null);
        ok(shop.owner, put(shop.base + "/items/" + old.get("id").asString() + "/placements/" + shop.bin), null);
        ok(
                shop.owner,
                post(shop.base + "/items/" + old.get("id").asString() + "/archive"),
                Map.of("expectedVersion", old.get("version").asLong()));
        location(shop.owner, shop.base, "Shelf B", shop.room);
        var archivedShelf = location(shop.owner, shop.base, "Shelf C", shop.room);
        ok(
                shop.owner,
                post(shop.base + "/locations/" + archivedShelf.get("id").asString() + "/archive"),
                Map.of("expectedVersion", archivedShelf.get("version").asLong()));

        var roots = ok(shop.owner, get(shop.base + "/locations?rootsOnly=true"), null);
        assertThat(find(roots, shop.room).get("childCount").asLong()).isEqualTo(2);
        assertThat(find(roots, shop.room).get("itemCount").asLong()).isZero();
        var shelves = ok(shop.owner, get(shop.base + "/locations?parentId=" + shop.shelf), null);
        assertThat(find(shelves, shop.bin).get("itemCount").asLong()).isEqualTo(2);
        assertThat(find(shelves, shop.bin).get("childCount").asLong()).isZero();
        ok(shop.owner, delete(shop.base + "/items/" + lids + "/placements/" + shop.bin), null);
        assertThat(find(ok(shop.owner, get(shop.base + "/locations?parentId=" + shop.shelf), null), shop.bin)
                        .get("itemCount")
                        .asLong())
                .isEqualTo(1);

        // Another business can't read the trail.
        var other = shop();
        assertThat(call(other.owner, get(other.base + "/locations/" + shop.bin), null)
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(shop.base + "/locations/" + shop.bin), null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void itemListsSortByNameOrNewestAndCarryCreatedAt() throws Exception {
        var shop = shop();
        var beans = item(shop.owner, shop.base, "Beans", "KILOGRAM").get("id").asString();
        var cups = item(shop.owner, shop.base, "Cups", "EACH");
        var apron = item(shop.owner, shop.base, "Apron", "EACH").get("id").asString();
        assertThat(cups.get("createdAt").asString()).isNotBlank();

        var names = new ArrayList<String>();
        ok(shop.owner, get(shop.base + "/items"), null)
                .get("items")
                .forEach(row -> names.add(row.get("name").asString()));
        assertThat(names).containsExactly("Apron", "Beans", "Cups");
        names.clear();
        ok(shop.owner, get(shop.base + "/items?sort=name_desc"), null)
                .get("items")
                .forEach(row -> names.add(row.get("name").asString()));
        assertThat(names).containsExactly("Cups", "Beans", "Apron");
        var newest = ok(shop.owner, get(shop.base + "/items?sort=newest"), null).get("items");
        assertThat(newest.get(0).get("id").asString()).isEqualTo(apron);
        assertThat(newest.get(2).get("id").asString()).isEqualTo(beans);
        assertThat(call(shop.owner, get(shop.base + "/items?sort=price"), null).status())
                .isEqualTo(400);
    }

    static byte[] jpeg(int width, int height) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(14, 107, 115));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    org.springframework.mock.web.MockHttpServletResponse upload(Client client, String url, String type, byte[] bytes)
            throws Exception {
        return mvc.perform(put(url).session(client.session())
                        .header("X-CSRF-TOKEN", client.csrf())
                        .contentType(type)
                        .content(bytes))
                .andReturn()
                .getResponse();
    }

    @Test
    void itemsCanHaveOneCheckedPhoto() throws Exception {
        var shop = shop();
        var item = item(shop.owner, shop.base, "Brew scale", "EACH").get("id").asString();
        var url = shop.base + "/items/" + item + "/photo";
        assertThat(ok(shop.owner, get(shop.base + "/items/" + item), null)
                        .get("photoVersion")
                        .isNull())
                .isTrue();
        assertThat(call(shop.owner, get(url), null).status()).isEqualTo(404);

        // Only real JPEGs within the limits are kept.
        assertThat(upload(shop.owner, url, "image/png", jpeg(10, 10)).getStatus())
                .isEqualTo(415);
        var garbage = new byte[2048];
        garbage[0] = (byte) 0xFF;
        garbage[1] = (byte) 0xD8;
        garbage[2] = (byte) 0xFF;
        assertThat(upload(shop.owner, url, "image/jpeg", garbage).getContentAsString())
                .contains("INVALID_IMAGE");
        assertThat(upload(shop.owner, url, "image/jpeg", new byte[700 * 1024]).getStatus())
                .isEqualTo(413);
        assertThat(upload(shop.owner, url, "image/jpeg", jpeg(3000, 20)).getContentAsString())
                .contains("IMAGE_TOO_BIG");
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(upload(employee, url, "image/jpeg", jpeg(40, 30)).getStatus())
                .isEqualTo(403);

        var saved = upload(shop.owner, url, "image/jpeg", jpeg(640, 480));
        assertThat(saved.getStatus()).isEqualTo(200);
        var version =
                mapper.readTree(saved.getContentAsString()).get("photoVersion").asLong();
        assertThat(ok(shop.owner, get(shop.base + "/items/" + item), null)
                        .get("photoVersion")
                        .asLong())
                .isEqualTo(version);
        assertThat(find(ok(shop.owner, get(shop.base + "/items"), null), item)
                        .get("photoVersion")
                        .asLong())
                .isEqualTo(version);

        // Everyone in the business can see it, privately cached; nobody outside can.
        var image = mvc.perform(get(url + "?v=" + version).session(employee.session()))
                .andReturn()
                .getResponse();
        assertThat(image.getStatus()).isEqualTo(200);
        assertThat(image.getContentType()).isEqualTo("image/jpeg");
        assertThat(image.getHeader("Cache-Control")).contains("private").contains("max-age=31536000");
        assertThat(image.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.getContentAsByteArray()).isNotEmpty();
        var other = shop();
        assertThat(call(other.owner, get(url), null).status()).isEqualTo(404);
        assertThat(upload(other.owner, url, "image/jpeg", jpeg(40, 30)).getStatus())
                .isEqualTo(404);

        // Replacing gives a new version; removing clears it.
        var replaced = mapper.readTree(
                        upload(shop.owner, url, "image/jpeg", jpeg(320, 240)).getContentAsString())
                .get("photoVersion")
                .asLong();
        assertThat(replaced).isGreaterThan(version);
        assertThat(call(shop.owner, delete(url), null).status()).isEqualTo(204);
        assertThat(ok(shop.owner, get(shop.base + "/items/" + item), null)
                        .get("photoVersion")
                        .isNull())
                .isTrue();
        assertThat(jdbc.queryForList(
                        "select action from audit_events where resource_id = ? and action like 'ITEM_PHOTO_%' order by created_at",
                        String.class, UUID.fromString(item)))
                .containsExactly("ITEM_PHOTO_SET", "ITEM_PHOTO_SET", "ITEM_PHOTO_REMOVED");
    }

    Response sell(Client client, Shop shop, String item, String quantity) throws Exception {
        return call(
                client,
                post(shop.base + "/inventory/removals"),
                Map.of(
                        "operationKey",
                        UUID.randomUUID().toString(),
                        "itemId",
                        item,
                        "locationId",
                        shop.bin,
                        "quantity",
                        quantity,
                        "reason",
                        "SALE",
                        "note",
                        "Counter sale"));
    }

    @Test
    void salesLeaveStockLikeAnyRemovalAndCanBeReversed() throws Exception {
        var shop = shop();
        var mugs = item(shop.owner, shop.base, "Branded mugs", "EACH").get("id").asString();
        receive(shop, mugs, shop.bin, "10");
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));

        var sale = sell(employee, shop, mugs, "3");
        assertThat(sale.status()).isEqualTo(201);
        assertThat(sale.json().get("type").asString()).isEqualTo("SALE");
        assertThat(sale.json().get("movements").get(0).get("balanceAfter").asString())
                .isEqualTo("7");
        var tooMany = sell(employee, shop, mugs, "8");
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.code()).isEqualTo("INSUFFICIENT_STOCK");

        var history = ok(shop.owner, get(shop.base + "/inventory/movements?itemId=" + mugs), null)
                .get("items");
        assertThat(history.get(0).get("operationType").asString()).isEqualTo("SALE");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'STOCK_SOLD' and resource_id = ?",
                        Integer.class,
                        UUID.fromString(mugs)))
                .isEqualTo(1);

        var csv = mvc.perform(get(shop.base + "/exports/movements.csv").session(shop.owner.session()))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(",Sold,Branded mugs,,Shelf A › Bin 3,each,-3,7,Test Person,,Counter sale,No,");

        // Reversing a sale puts the stock back, like any removal.
        var reversed = call(
                shop.owner,
                post(shop.base + "/inventory/operations/"
                        + sale.json().get("id").asString() + "/reverse"),
                Map.of("operationKey", UUID.randomUUID().toString()));
        assertThat(reversed.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "select quantity from stock_balances where item_id = ?",
                        java.math.BigDecimal.class,
                        UUID.fromString(mugs)))
                .isEqualByComparingTo("10");
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/removals"),
                                Map.of(
                                        "operationKey",
                                        UUID.randomUUID().toString(),
                                        "itemId",
                                        mugs,
                                        "locationId",
                                        shop.bin,
                                        "quantity",
                                        "1",
                                        "reason",
                                        "REFUND"))
                        .status())
                .isEqualTo(400);
    }
}
