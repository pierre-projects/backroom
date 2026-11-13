package com.backroom.labels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/** Codes, scan resolution and label PDFs against real PostgreSQL. */
@IntegrationTest
class LabelsIntegrationTests extends ApiTestSupport {
    record Shop(Client owner, String base, String item, String room, String shelf) {}

    Shop shop() throws Exception {
        var owner = register();
        var base = base(org(owner));
        var room = location(owner, base, "Stockroom", null).get("id").asString();
        var shelf = location(owner, base, "Shelf A", room).get("id").asString();
        var item = item(owner, base, "Espresso beans", "KILOGRAM").get("id").asString();
        return new Shop(owner, base, item, room, shelf);
    }

    JsonNode qr(Client client, String base, String type, String id) throws Exception {
        return ok(client, post(base + "/codes"), Map.of("type", type, "id", id));
    }

    Response scan(Client client, String base, String value) throws Exception {
        return call(client, post(base + "/scan-resolutions"), Map.of("value", value));
    }

    String status(Client client, String base, String value) throws Exception {
        var response = scan(client, base, value);
        assertThat(response.status())
                .withFailMessage(response.json().toString())
                .isEqualTo(200);
        return response.json().get("status").asString();
    }

    byte[] pdf(Client client, String base, Object body) throws Exception {
        var result = mvc.perform(post(base + "/labels.pdf")
                        .session(client.session())
                        .header("X-CSRF-TOKEN", client.csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .withFailMessage(result.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        return result.getResponse().getContentAsByteArray();
    }

    @Test
    void qrCodesAreReusedAndKeepResolvingAfterRenamesAndMoves() throws Exception {
        var shop = shop();
        var code = qr(shop.owner, shop.base, "ITEM", shop.item);
        assertThat(qr(shop.owner, shop.base, "ITEM", shop.item).get("id").asString())
                .isEqualTo(code.get("id").asString());
        assertThat(code.get("display").asString())
                .matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{2}");
        assertThat(code.get("url").asString())
                .endsWith("/s/" + code.get("display").asString().replace("-", ""));

        ok(
                shop.owner,
                patch(shop.base + "/items/" + shop.item),
                Map.of("name", "House espresso", "unit", "KILOGRAM", "expectedVersion", 0));
        var byUrl = scan(shop.owner, shop.base, code.get("url").asString()).json();
        assertThat(byUrl.get("status").asString()).isEqualTo("ITEM");
        assertThat(byUrl.get("name").asString()).isEqualTo("House espresso");
        assertThat(scan(shop.owner, shop.base, code.get("display").asString().toLowerCase())
                        .json()
                        .get("id")
                        .asString())
                .isEqualTo(shop.item);

        var shelfCode = qr(shop.owner, shop.base, "LOCATION", shop.shelf);
        var other =
                location(shop.owner, shop.base, "Coffee bar", null).get("id").asString();
        ok(
                shop.owner,
                patch(shop.base + "/locations/" + shop.shelf),
                Map.of("name", "Shelf A", "type", "SHELF", "parentId", other, "expectedVersion", 0));
        var location =
                scan(shop.owner, shop.base, shelfCode.get("display").asString()).json();
        assertThat(location.get("status").asString()).isEqualTo("LOCATION");
        assertThat(location.get("parentName").asString()).isEqualTo("Coffee bar");
        assertThat(ok(shop.owner, get(shop.base + "/codes?itemId=" + shop.item), null))
                .hasSize(1);
        assertThat(ok(shop.owner, get(shop.base + "/audit-events?size=50"), null)
                        .toString())
                .contains("CODE_CREATED");
    }

    @Test
    void revokedCodesStayRevokedAndAreNeverReassigned() throws Exception {
        var shop = shop();
        var code = qr(shop.owner, shop.base, "ITEM", shop.item);
        var id = code.get("id").asString();
        ok(
                shop.owner,
                post(shop.base + "/codes/" + id + "/revoke"),
                Map.of("expectedVersion", code.get("version").asLong()));
        assertThat(status(shop.owner, shop.base, code.get("display").asString()))
                .isEqualTo("REVOKED");
        var replacement = qr(shop.owner, shop.base, "ITEM", shop.item);
        assertThat(replacement.get("display").asString())
                .isNotEqualTo(code.get("display").asString());
        var uuid = UUID.fromString(id);
        assertThatThrownBy(() -> jdbc.update(
                        "update resource_codes set status='ACTIVE', revoked_at=null, revoked_by=null where id=?", uuid))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("revoked");
        assertThatThrownBy(() -> jdbc.update(
                        "update resource_codes set item_id=null, location_id=? where id=?",
                        UUID.fromString(shop.shelf),
                        uuid))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("reassigned");
        assertThatThrownBy(() -> jdbc.update("delete from resource_codes where id=?", uuid))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void registersBarcodesOncePerOrganizationAndOnlyByManagers() throws Exception {
        var shop = shop();
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        var syrup = item(shop.owner, shop.base, "Vanilla syrup", "MILLILITER")
                .get("id")
                .asString();

        var registered =
                ok(shop.owner, post(shop.base + "/items/" + shop.item + "/barcodes"), Map.of("value", "036000291452"));
        assertThat(registered.get("display").asString()).isEqualTo("036000291452");
        assertThat(scan(shop.owner, shop.base, "0036000291452").json().get("id").asString())
                .isEqualTo(shop.item);
        assertThat(call(shop.owner, post(shop.base + "/items/" + syrup + "/barcodes"), Map.of("value", "036000291452"))
                        .code())
                .isEqualTo("BARCODE_IN_USE");
        assertThat(call(shop.owner, post(shop.base + "/items/" + syrup + "/barcodes"), Map.of("value", "036000291453"))
                        .code())
                .isEqualTo("INVALID_BARCODE");
        assertThat(call(employee, post(shop.base + "/items/" + syrup + "/barcodes"), Map.of("value", "4006381333931"))
                        .status())
                .isEqualTo(403);

        var unknown = scan(employee, shop.base, "4006381333931").json();
        assertThat(unknown.get("status").asString()).isEqualTo("NOT_REGISTERED");
        assertThat(unknown.get("canRegister").asBoolean()).isFalse();
        assertThat(scan(shop.owner, shop.base, "4006381333931")
                        .json()
                        .get("canRegister")
                        .asBoolean())
                .isTrue();

        ok(
                shop.owner,
                post(shop.base + "/codes/" + registered.get("id").asString() + "/revoke"),
                Map.of("expectedVersion", registered.get("version").asLong()));
        ok(shop.owner, post(shop.base + "/items/" + syrup + "/barcodes"), Map.of("value", "036000291452"));
        assertThat(scan(employee, shop.base, "036000291452").json().get("id").asString())
                .isEqualTo(syrup);

        ok(
                shop.owner,
                post(shop.base + "/items/" + shop.item + "/barcodes"),
                Map.of("value", "04252614", "format", "upc_e"));
        assertThat(scan(shop.owner, shop.base, "042100005264").json().get("id").asString())
                .isEqualTo(shop.item);
        assertThat(call(employee, post(shop.base + "/codes"), Map.of("type", "ITEM", "id", shop.item))
                        .status())
                .isEqualTo(403);
    }

    @Test
    void explainsEveryKindOfUnusableScanWithoutLeakingOtherBusinesses() throws Exception {
        var shop = shop();
        assertThat(status(shop.owner, shop.base, "https://example.com/s/7K4Q2M9XQA"))
                .isEqualTo("UNSUPPORTED");
        assertThat(status(shop.owner, shop.base, "Hello, world!")).isEqualTo("UNSUPPORTED");
        assertThat(status(shop.owner, shop.base, "ZZZZ-ZZZZ-ZZ")).isEqualTo("NOT_FOUND");

        var secondBase = base(org(shop.owner));
        var secondItem =
                item(shop.owner, secondBase, "Napkins", "EACH").get("id").asString();
        var foreign = qr(shop.owner, secondBase, "ITEM", secondItem);
        var other =
                scan(shop.owner, shop.base, foreign.get("display").asString()).json();
        assertThat(other.get("status").asString()).isEqualTo("OTHER_ORGANIZATION");
        assertThat(other.get("organizationName").asString()).startsWith("Business ");
        assertThat(other.has("name") && !other.get("name").isNull()).isFalse();

        var stranger = shop();
        assertThat(status(stranger.owner, stranger.base, foreign.get("display").asString()))
                .isEqualTo("NOT_FOUND");
        assertThat(call(
                                stranger.owner,
                                get("/api/v1/codes/"
                                        + foreign.get("display").asString().replace("-", "")),
                                null)
                        .status())
                .isEqualTo(404);

        var link = ok(shop.owner, get("/api/v1/codes/" + foreign.get("display").asString()), null);
        assertThat(link.get("status").asString()).isEqualTo("ITEM");
        assertThat(link.get("id").asString()).isEqualTo(secondItem);
        assertThat(call(shop.owner, get("/api/v1/codes/not-a-token"), null).status())
                .isEqualTo(404);
        mvc.perform(get("/api/v1/codes/" + foreign.get("display").asString()))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));

        var code = qr(shop.owner, shop.base, "ITEM", shop.item);
        ok(shop.owner, post(shop.base + "/items/" + shop.item + "/archive"), Map.of("expectedVersion", 0));
        var archived =
                scan(shop.owner, shop.base, code.get("display").asString()).json();
        assertThat(archived.get("status").asString()).isEqualTo("ITEM");
        assertThat(archived.get("archived").asBoolean()).isTrue();
        var fresh = item(shop.owner, shop.base, "Old cups", "EACH").get("id").asString();
        ok(shop.owner, post(shop.base + "/items/" + fresh + "/archive"), Map.of("expectedVersion", 0));
        assertThat(call(shop.owner, post(shop.base + "/codes"), Map.of("type", "ITEM", "id", fresh))
                        .code())
                .isEqualTo("ARCHIVED");
    }

    @Test
    void printsBoundedLabelSheetsForManagersOnly() throws Exception {
        var shop = shop();
        var targets = List.of(Map.of("type", "ITEM", "id", shop.item), Map.of("type", "LOCATION", "id", shop.shelf));
        try (var document = Loader.loadPDF(
                pdf(shop.owner, shop.base, Map.of("targets", targets, "layout", "A4_21", "startPosition", 21)))) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }
        try (var document =
                Loader.loadPDF(pdf(shop.owner, shop.base, Map.of("targets", targets, "layout", "SINGLE")))) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(ok(shop.owner, get(shop.base + "/codes?locationId=" + shop.shelf), null))
                .hasSize(1);

        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(call(employee, post(shop.base + "/labels.pdf"), Map.of("targets", targets, "layout", "A4_21"))
                        .status())
                .isEqualTo(403);
        var tooMany = Collections.nCopies(106, Map.of("type", "ITEM", "id", shop.item));
        assertThat(call(shop.owner, post(shop.base + "/labels.pdf"), Map.of("targets", tooMany, "layout", "A4_21"))
                        .status())
                .isEqualTo(400);
        var calibration = mvc.perform(get(shop.base + "/labels/calibration.pdf?layout=LETTER_10")
                        .session(shop.owner.session()))
                .andReturn();
        assertThat(calibration.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(ok(shop.owner, get(shop.base + "/labels/settings"), null)
                        .get("maxLabels")
                        .asInt())
                .isEqualTo(105);
    }
}
