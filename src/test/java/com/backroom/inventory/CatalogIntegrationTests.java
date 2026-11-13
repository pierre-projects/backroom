package com.backroom.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Item lists and sorting, and item photos. */
@IntegrationTest
class CatalogIntegrationTests extends ApiTestSupport {
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

    JsonNode find(JsonNode page, String id) {
        for (var row : page.get("items")) if (row.get("id").asString().equals(id)) return row;
        throw new AssertionError("Missing " + id);
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
}
