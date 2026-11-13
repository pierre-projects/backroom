package com.backroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.from;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.backroom.audit.AuditService;
import com.backroom.inventory.StockCommandHooks;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Phase 2 stock behaviour against real PostgreSQL, including the PRD §13 concurrency and retry scenarios. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTests.PostgresConfiguration.class)
class InventoryIntegrationTests extends ApiTestSupport {
    @MockitoSpyBean
    AuditService audit;

    @MockitoSpyBean
    StockCommandHooks hooks;

    @AfterEach
    void resetSpies() {
        reset(audit, hooks);
    }

    record Shop(Client owner, String base, UUID orgId, String item, String room, String shelfA, String shelfB) {}

    Shop shop(String unit) throws Exception {
        var owner = register();
        var org = org(owner);
        var base = base(org);
        var room = location(owner, base, "Stockroom", null).get("id").asString();
        var a = location(owner, base, "Shelf A", room).get("id").asString();
        var b = location(owner, base, "Shelf B", room).get("id").asString();
        var item = item(owner, base, "Espresso beans", unit).get("id").asString();
        return new Shop(owner, base, UUID.fromString(org.get("id").asString()), item, room, a, b);
    }

    Map<String, Object> body(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        map.put("operationKey", UUID.randomUUID().toString());
        for (int index = 0; index < pairs.length; index += 2) map.put((String) pairs[index], pairs[index + 1]);
        return map;
    }

    Response receive(Client client, Shop shop, String location, String quantity) throws Exception {
        return call(
                client,
                post(shop.base + "/inventory/receipts"),
                body("itemId", shop.item, "locationId", location, "quantity", quantity));
    }

    Response use(Client client, Shop shop, String location, String quantity) throws Exception {
        return call(
                client,
                post(shop.base + "/inventory/removals"),
                body("itemId", shop.item, "locationId", location, "quantity", quantity, "reason", "USAGE"));
    }

    Response transfer(Client client, Shop shop, String from, String to, String quantity) throws Exception {
        return call(
                client,
                post(shop.base + "/inventory/transfers"),
                body("itemId", shop.item, "fromLocationId", from, "toLocationId", to, "quantity", quantity));
    }

    long version(Shop shop, String location) throws Exception {
        for (var balance : ok(shop.owner, get(shop.base + "/inventory/balances?itemId=" + shop.item), null)) {
            if (balance.get("locationId").asString().equals(location))
                return balance.get("version").asLong();
        }
        throw new AssertionError("No balance for " + location);
    }

    BigDecimal balance(Shop shop, String location) {
        var values = jdbc.queryForList(
                "select quantity from stock_balances where item_id=? and location_id=?",
                BigDecimal.class,
                UUID.fromString(shop.item),
                UUID.fromString(location));
        return values.isEmpty() ? BigDecimal.ZERO : values.getFirst().stripTrailingZeros();
    }

    int rows(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }
    /** The movement ledger must explain every balance exactly. */
    void assertLedgerMatches(Shop shop) {
        var mismatches = rows("""
                select count(*) from stock_balances b
                where b.item_id = ? and b.quantity <> coalesce((select sum(m.quantity_change) from stock_movements m
                      where m.item_id = b.item_id and m.location_id = b.location_id), 0)
                """, UUID.fromString(shop.item));
        assertThat(mismatches).isZero();
    }

    /** Holds the first command that reaches the hook until the second is confirmed to be waiting on a database lock. */
    List<Integer> holdFirstThen(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    if (armed.compareAndSet(true, false)) {
                        reached.countDown();
                        release.await(30, TimeUnit.SECONDS);
                    }
                    return null;
                })
                .when(hooks)
                .afterBalancesLocked(any(), any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(first);
            assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
            var b = executor.submit(second);
            awaitBlockedOnLocks(1);
            release.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void recordsReceiptsUsageWasteTransfersCountsAndReversalsWithHistory() throws Exception {
        var shop = shop("KILOGRAM");
        var received = receive(shop.owner, shop, shop.shelfA, "10");
        assertThat(received.status()).isEqualTo(201);
        assertThat(received.json().get("movements").get(0).get("balanceAfter").asString())
                .isEqualTo("10");
        assertThat(ok(shop.owner, get(shop.base + "/items/" + shop.item + "/placements"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(1);
        assertThat(use(shop.owner, shop, shop.shelfA, "2.5").status()).isEqualTo(201);
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/removals"),
                                body(
                                        "itemId",
                                        shop.item,
                                        "locationId",
                                        shop.shelfA,
                                        "quantity",
                                        "0.5",
                                        "reason",
                                        "WASTE"))
                        .status())
                .isEqualTo(201);
        var moved = transfer(shop.owner, shop, shop.shelfA, shop.shelfB, "3");
        assertThat(moved.status()).isEqualTo(201);
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("4");
        assertThat(balance(shop, shop.shelfB)).isEqualByComparingTo("3");

        var counted = ok(
                shop.owner,
                post(shop.base + "/inventory/counts"),
                body(
                        "itemId",
                        shop.item,
                        "locationId",
                        shop.shelfA,
                        "countedQuantity",
                        "5",
                        "expectedBalanceVersion",
                        version(shop, shop.shelfA)));
        assertThat(counted.get("count").get("difference").asString()).isEqualTo("1");
        var matching = ok(
                shop.owner,
                post(shop.base + "/inventory/counts"),
                body(
                        "itemId",
                        shop.item,
                        "locationId",
                        shop.shelfA,
                        "countedQuantity",
                        "5",
                        "expectedBalanceVersion",
                        version(shop, shop.shelfA)));
        assertThat(matching.get("movements")).isEmpty();
        assertThat(rows("select count(*) from stock_counts where item_id=?", UUID.fromString(shop.item)))
                .isEqualTo(2);

        var reversed = ok(
                shop.owner,
                post(shop.base + "/inventory/operations/"
                        + moved.json().get("id").asString() + "/reverse"),
                body());
        assertThat(reversed.get("type").asString()).isEqualTo("REVERSAL");
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("8");
        assertThat(balance(shop, shop.shelfB)).isEqualByComparingTo("0");

        var history = ok(shop.owner, get(shop.base + "/inventory/movements?itemId=" + shop.item), null);
        assertThat(history.get("totalElements").asInt()).isEqualTo(8);
        var transferRows = new ArrayList<JsonNode>();
        history.get("items").forEach(row -> {
            if (row.get("operationType").asString().equals("TRANSFER")) transferRows.add(row);
        });
        assertThat(transferRows)
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("reversed").asBoolean()).isTrue());
        assertThat(history.get("items").get(0).get("parentName").asString()).isEqualTo("Stockroom");
        assertThat(ok(shop.owner, get(shop.base + "/items"), null)
                        .get("items")
                        .get(0)
                        .get("onHand")
                        .asString())
                .isEqualTo("8");
        assertThat(ok(shop.owner, get(shop.base + "/locations/" + shop.shelfA + "/items"), null)
                        .get("items")
                        .get(0)
                        .get("onHand")
                        .asString())
                .isEqualTo("8");
        assertThat(ok(shop.owner, get(shop.base + "/audit-events?size=50"), null)
                        .toString())
                .contains(
                        "STOCK_RECEIVED",
                        "STOCK_USED",
                        "STOCK_WASTED",
                        "STOCK_TRANSFERRED",
                        "STOCK_COUNTED",
                        "STOCK_REVERSED");
        assertLedgerMatches(shop);
    }

    @Test
    void enforcesQuantityAndWorkflowRules() throws Exception {
        var shop = shop("EACH");
        assertThat(receive(shop.owner, shop, shop.shelfA, "1.5").code()).isEqualTo("INVALID_QUANTITY");
        assertThat(receive(shop.owner, shop, shop.shelfA, "0").code()).isEqualTo("INVALID_QUANTITY");
        assertThat(receive(shop.owner, shop, shop.shelfA, "-2").code()).isEqualTo("INVALID_QUANTITY");
        assertThat(receive(shop.owner, shop, shop.room, "2").code()).isEqualTo("LOCATION_NOT_LEAF");
        var kilos = item(shop.owner, shop.base, "Sugar", "KILOGRAM").get("id").asString();
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/receipts"),
                                body("itemId", kilos, "locationId", shop.shelfA, "quantity", "1.2345"))
                        .code())
                .isEqualTo("INVALID_QUANTITY");

        var opening = call(
                shop.owner,
                post(shop.base + "/inventory/opening-balances"),
                body("itemId", shop.item, "locationId", shop.shelfA, "quantity", "4"));
        assertThat(opening.status()).isEqualTo(201);
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/opening-balances"),
                                body("itemId", shop.item, "locationId", shop.shelfA, "quantity", "4"))
                        .code())
                .isEqualTo("OPENING_ALREADY_RECORDED");
        assertThat(use(shop.owner, shop, shop.shelfA, "5").code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(use(shop.owner, shop, shop.shelfB, "1").code()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(transfer(shop.owner, shop, shop.shelfA, shop.shelfA, "1").code())
                .isEqualTo("SAME_LOCATION");

        var stale = version(shop, shop.shelfA);
        assertThat(receive(shop.owner, shop, shop.shelfA, "1").status()).isEqualTo(201);
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/counts"),
                                body(
                                        "itemId",
                                        shop.item,
                                        "locationId",
                                        shop.shelfA,
                                        "countedQuantity",
                                        "3",
                                        "expectedBalanceVersion",
                                        stale))
                        .code())
                .isEqualTo("STALE_COUNT");
        assertThat(rows("select count(*) from stock_counts where item_id=?", UUID.fromString(shop.item)))
                .isZero();
        var count = ok(
                shop.owner,
                post(shop.base + "/inventory/counts"),
                body(
                        "itemId",
                        shop.item,
                        "locationId",
                        shop.shelfA,
                        "countedQuantity",
                        "5",
                        "expectedBalanceVersion",
                        version(shop, shop.shelfA)));
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/inventory/operations/"
                                        + count.get("id").asString() + "/reverse"),
                                body())
                        .code())
                .isEqualTo("NOT_REVERSIBLE");

        // A receipt can't be reversed once its stock has been used.
        var receipt =
                receive(shop.owner, shop, shop.shelfB, "2").json().get("id").asString();
        assertThat(use(shop.owner, shop, shop.shelfB, "2").status()).isEqualTo(201);
        assertThat(call(shop.owner, post(shop.base + "/inventory/operations/" + receipt + "/reverse"), body())
                        .code())
                .isEqualTo("INSUFFICIENT_STOCK");
        var usage = use(shop.owner, shop, shop.shelfA, "1").json().get("id").asString();
        ok(shop.owner, post(shop.base + "/inventory/operations/" + usage + "/reverse"), body());
        assertThat(call(shop.owner, post(shop.base + "/inventory/operations/" + usage + "/reverse"), body())
                        .code())
                .isEqualTo("ALREADY_REVERSED");
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("5");
        assertLedgerMatches(shop);
    }

    @Test
    void retriesWithTheSameKeyApplyOnceAndRejectDifferentContents() throws Exception {
        var shop = shop("KILOGRAM");
        var manager = register();
        join(manager, invitation(shop.owner, shop.base, "MANAGER"));
        var request = body("itemId", shop.item, "locationId", shop.shelfA, "quantity", "2.25", "reference", "INV-7");
        var first = call(shop.owner, post(shop.base + "/inventory/receipts"), request);
        var retry = call(shop.owner, post(shop.base + "/inventory/receipts"), request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().get("replayed").asBoolean()).isTrue();
        assertThat(retry.json().get("id").asString())
                .isEqualTo(first.json().get("id").asString());
        var changed = new LinkedHashMap<>(request);
        changed.put("quantity", "3");
        assertThat(call(shop.owner, post(shop.base + "/inventory/receipts"), changed)
                        .code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(call(manager, post(shop.base + "/inventory/receipts"), request)
                        .code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("2.25");
        assertThat(rows("select count(*) from stock_operations where item_id=?", UUID.fromString(shop.item)))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateReceiptProducesOneOperationAndOneIncrease() throws Exception {
        var shop = shop("EACH");
        var request = body("itemId", shop.item, "locationId", shop.shelfA, "quantity", "6");
        var statuses = holdFirstThen(
                () -> call(shop.owner, post(shop.base + "/inventory/receipts"), request)
                        .status(),
                () -> call(shop.owner, post(shop.base + "/inventory/receipts"), request)
                        .status());
        assertThat(statuses).containsExactly(201, 200);
        assertThat(rows("select count(*) from stock_operations where item_id=?", UUID.fromString(shop.item)))
                .isEqualTo(1);
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("6");
    }

    @Test
    void concurrentRemovalsCannotTakeMoreThanIsThere() throws Exception {
        var shop = shop("EACH");
        receive(shop.owner, shop, shop.shelfA, "5");
        var statuses = holdFirstThen(
                () -> use(shop.owner, shop, shop.shelfA, "4").status(),
                () -> use(shop.owner, shop, shop.shelfA, "4").status());
        assertThat(statuses).containsExactly(201, 409);
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("1");
        assertLedgerMatches(shop);
    }

    @Test
    void opposingTransfersCompleteWithoutDeadlockAndPreserveTotals() throws Exception {
        var shop = shop("EACH");
        receive(shop.owner, shop, shop.shelfA, "5");
        receive(shop.owner, shop, shop.shelfB, "5");
        var statuses = holdFirstThen(
                () -> transfer(shop.owner, shop, shop.shelfA, shop.shelfB, "2").status(),
                () -> transfer(shop.owner, shop, shop.shelfB, shop.shelfA, "3").status());
        assertThat(statuses).containsExactly(201, 201);
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("6");
        assertThat(balance(shop, shop.shelfB)).isEqualByComparingTo("4");
        assertLedgerMatches(shop);
    }

    @Test
    void failedTransferCommitsNeitherSide() throws Exception {
        var shop = shop("EACH");
        receive(shop.owner, shop, shop.shelfA, "5");
        var operations = rows("select count(*) from stock_operations where item_id=?", UUID.fromString(shop.item));
        doThrow(new IllegalStateException("Forced audit failure for rollback verification"))
                .when(audit)
                .record(any(), any(), anyString(), eq("STOCK_TRANSFERRED"), anyString(), any(), anyMap());
        assertThat(transfer(shop.owner, shop, shop.shelfA, shop.shelfB, "2").status())
                .isEqualTo(500);
        assertThat(balance(shop, shop.shelfA)).isEqualByComparingTo("5");
        assertThat(balance(shop, shop.shelfB)).isEqualByComparingTo("0");
        assertThat(rows("select count(*) from stock_operations where item_id=?", UUID.fromString(shop.item)))
                .isEqualTo(operations);
        assertLedgerMatches(shop);
    }

    @Test
    void archivingWaitsForAnInFlightReceiptAndThenRefuses() throws Exception {
        var shop = shop("EACH");
        var version = ok(shop.owner, get(shop.base + "/items/" + shop.item), null)
                .get("version")
                .asLong();
        var statuses = holdFirstThen(
                () -> receive(shop.owner, shop, shop.shelfA, "3").status(),
                () -> call(
                                shop.owner,
                                post(shop.base + "/items/" + shop.item + "/archive"),
                                Map.of("expectedVersion", version))
                        .status());
        assertThat(statuses).containsExactly(201, 409);
        assertThat(ok(shop.owner, get(shop.base + "/items/" + shop.item), null)
                        .get("archived")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void guardsUnitsArchivalAndPlacementsWhileStockRemains() throws Exception {
        var shop = shop("KILOGRAM");
        ok(
                shop.owner,
                patch(shop.base + "/items/" + shop.item),
                Map.of("name", "Espresso beans", "unit", "GRAM", "expectedVersion", 0));
        ok(
                shop.owner,
                patch(shop.base + "/items/" + shop.item),
                Map.of("name", "Espresso beans", "unit", "KILOGRAM", "expectedVersion", 1));
        receive(shop.owner, shop, shop.shelfA, "2");
        assertThat(call(
                                shop.owner,
                                patch(shop.base + "/items/" + shop.item),
                                Map.of("name", "Espresso beans", "unit", "GRAM", "expectedVersion", 2))
                        .code())
                .isEqualTo("UNIT_LOCKED");
        ok(
                shop.owner,
                patch(shop.base + "/items/" + shop.item),
                Map.of("name", "House espresso", "unit", "KILOGRAM", "expectedVersion", 2));
        assertThat(call(shop.owner, post(shop.base + "/items/" + shop.item + "/archive"), Map.of("expectedVersion", 3))
                        .code())
                .isEqualTo("ITEM_HAS_STOCK");
        assertThat(call(shop.owner, delete(shop.base + "/items/" + shop.item + "/placements/" + shop.shelfA), null)
                        .code())
                .isEqualTo("PLACEMENT_HAS_STOCK");
        var locationVersion = ok(shop.owner, get(shop.base + "/locations/" + shop.shelfA), null)
                .get("version")
                .asLong();
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/locations/" + shop.shelfA + "/archive"),
                                Map.of("expectedVersion", locationVersion))
                        .code())
                .isEqualTo("LOCATION_HAS_STOCK");
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/locations"),
                                Map.of("name", "Bin", "type", "BIN", "parentId", shop.shelfA))
                        .code())
                .isEqualTo("LOCATION_HAS_STOCK");

        use(shop.owner, shop, shop.shelfA, "2");
        ok(shop.owner, delete(shop.base + "/items/" + shop.item + "/placements/" + shop.shelfA), null);
        assertThat(ok(shop.owner, get(shop.base + "/inventory/balances?itemId=" + shop.item), null))
                .isEmpty();
        assertThat(receive(shop.owner, shop, shop.shelfA, "1").status()).isEqualTo(201); // reactivates the placement
        use(shop.owner, shop, shop.shelfA, "1");
        ok(shop.owner, post(shop.base + "/items/" + shop.item + "/archive"), Map.of("expectedVersion", 3));
        assertThat(receive(shop.owner, shop, shop.shelfA, "1").code()).isEqualTo("ARCHIVED");
    }

    @Test
    void appliesRolesAndKeepsOrganizationsIsolated() throws Exception {
        var shop = shop("EACH");
        var employee = register();
        join(employee, invitation(shop.owner, shop.base, "EMPLOYEE"));
        assertThat(receive(employee, shop, shop.shelfA, "4").status()).isEqualTo(201);
        assertThat(use(employee, shop, shop.shelfA, "1").status()).isEqualTo(201);
        var receipt =
                receive(shop.owner, shop, shop.shelfA, "1").json().get("id").asString();
        assertThat(transfer(employee, shop, shop.shelfA, shop.shelfB, "1").status())
                .isEqualTo(403);
        assertThat(call(
                                employee,
                                post(shop.base + "/inventory/counts"),
                                body(
                                        "itemId",
                                        shop.item,
                                        "locationId",
                                        shop.shelfA,
                                        "countedQuantity",
                                        "1",
                                        "expectedBalanceVersion",
                                        version(shop, shop.shelfA)))
                        .status())
                .isEqualTo(403);
        assertThat(call(
                                employee,
                                post(shop.base + "/inventory/opening-balances"),
                                body("itemId", shop.item, "locationId", shop.shelfB, "quantity", "1"))
                        .status())
                .isEqualTo(403);
        assertThat(call(employee, post(shop.base + "/inventory/operations/" + receipt + "/reverse"), body())
                        .status())
                .isEqualTo(403);
        assertThat(call(employee, get(shop.base + "/inventory/movements?itemId=" + shop.item), null)
                        .status())
                .isEqualTo(200);
        assertThat(call(employee, get(shop.base + "/audit-events"), null).status())
                .isEqualTo(403);

        var other = shop("EACH");
        var before = rows("select count(*) from stock_operations");
        assertThat(receive(
                                other.owner,
                                new Shop(
                                        other.owner,
                                        other.base,
                                        other.orgId,
                                        shop.item,
                                        other.room,
                                        other.shelfA,
                                        other.shelfB),
                                other.shelfA,
                                "1")
                        .status())
                .isEqualTo(404);
        assertThat(receive(
                                other.owner,
                                new Shop(
                                        other.owner,
                                        other.base,
                                        other.orgId,
                                        other.item,
                                        other.room,
                                        shop.shelfA,
                                        other.shelfB),
                                shop.shelfA,
                                "1")
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, post(other.base + "/inventory/operations/" + receipt + "/reverse"), body())
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(other.base + "/inventory/balances?itemId=" + shop.item), null)
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(other.base + "/inventory/movements?locationId=" + shop.shelfA), null)
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(shop.base + "/inventory/movements?itemId=" + shop.item), null)
                        .status())
                .isEqualTo(404);
        assertThat(rows("select count(*) from stock_operations")).isEqualTo(before);
    }

    @Test
    void committedHistoryCannotBeEditedOrDeleted() throws Exception {
        var shop = shop("EACH");
        receive(shop.owner, shop, shop.shelfA, "2");
        assertThatThrownBy(() -> jdbc.update(
                        "update stock_movements set quantity_change = 99 where item_id = ?",
                        UUID.fromString(shop.item)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () -> jdbc.update("delete from stock_operations where item_id = ?", UUID.fromString(shop.item)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                        "update stock_balances set quantity = -1 where item_id = ?", UUID.fromString(shop.item)))
                .isInstanceOf(DataAccessException.class);
    }
}
