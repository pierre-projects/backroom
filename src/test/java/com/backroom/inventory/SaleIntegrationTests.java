package com.backroom.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sales are removals: they leave stock like any other and can be reversed. */
@IntegrationTest
class SaleIntegrationTests extends ApiTestSupport {
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
