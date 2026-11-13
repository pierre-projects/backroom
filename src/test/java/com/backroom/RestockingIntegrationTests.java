package com.backroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.backroom.audit.AuditService;
import com.backroom.inventory.StockCommandHooks;
import java.math.BigDecimal;
import java.util.HashMap;
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

/** Phase 4 restock requests: workflow rules, roles, idempotent deliveries and their concurrency, against real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTests.PostgresConfiguration.class)
class RestockingIntegrationTests extends ApiTestSupport {
    @MockitoSpyBean
    AuditService audit;

    @MockitoSpyBean
    StockCommandHooks hooks;

    @AfterEach
    void resetSpies() {
        reset(audit, hooks);
    }

    record Shop(Client owner, Client employee, String base, String item, String shelf) {}

    Shop shop() throws Exception {
        var owner = register();
        var base = base(org(owner));
        var room = location(owner, base, "Stockroom", null).get("id").asString();
        var shelf = location(owner, base, "Shelf A", room).get("id").asString();
        var item = item(owner, base, "Oat milk", "LITER").get("id").asString();
        var employee = register();
        join(employee, invitation(owner, base, "EMPLOYEE"));
        return new Shop(owner, employee, base, item, shelf);
    }

    JsonNode submit(Client client, Shop shop, String quantity) throws Exception {
        return ok(
                client,
                post(shop.base + "/requests"),
                Map.of("itemId", shop.item, "locationId", shop.shelf, "quantity", quantity, "note", "Running low"));
    }

    JsonNode current(Shop shop, String id) throws Exception {
        return ok(shop.owner, get(shop.base + "/requests/" + id), null);
    }

    JsonNode approve(Shop shop, String id, String quantity) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("expectedVersion", current(shop, id).get("version").asLong());
        if (quantity != null) body.put("approvedQuantity", quantity);
        return ok(shop.owner, post(shop.base + "/requests/" + id + "/approve"), body);
    }

    Map<String, Object> delivery(String quantity) {
        return Map.of("operationKey", UUID.randomUUID().toString(), "quantity", quantity);
    }

    Response receive(Client client, Shop shop, String id, Object body) throws Exception {
        return call(client, post(shop.base + "/requests/" + id + "/receipts"), body);
    }

    BigDecimal stock(Shop shop) {
        var values = jdbc.queryForList(
                "select quantity from stock_balances where item_id=? and location_id=?",
                BigDecimal.class,
                UUID.fromString(shop.item),
                UUID.fromString(shop.shelf));
        return values.isEmpty() ? BigDecimal.ZERO : values.getFirst();
    }

    int rows(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }

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
    void requestsMoveFromSubmissionThroughPartialDeliveriesToReceived() throws Exception {
        var shop = shop();
        var request = submit(shop.employee, shop, "10");
        var id = request.get("id").asString();
        assertThat(request.get("label").asString()).isEqualTo("R-1");
        assertThat(request.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submit(shop.employee, shop, "1").get("number").asInt()).isEqualTo(2);

        var approved = approve(shop, id, "6");
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("requested").asString()).isEqualTo("10");
        assertThat(approved.get("approved").asString()).isEqualTo("6");
        assertThat(stock(shop)).isEqualByComparingTo("0");

        var first = receive(shop.employee, shop, id, delivery("4"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.json().get("status").asString()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(first.json().get("remaining").asString()).isEqualTo("2");
        assertThat(receive(shop.employee, shop, id, delivery("3")).code()).isEqualTo("OVER_RECEIPT");
        var done = receive(shop.owner, shop, id, delivery("2")).json();
        assertThat(done.get("status").asString()).isEqualTo("FULFILLED");
        assertThat(done.get("receipts")).hasSize(2);
        assertThat(stock(shop)).isEqualByComparingTo("6");
        assertThat(receive(shop.owner, shop, id, delivery("1")).code()).isEqualTo("INVALID_TRANSITION");

        var history = ok(shop.owner, get(shop.base + "/inventory/movements?itemId=" + shop.item), null);
        assertThat(history.get("items").get(0).get("reference").asString()).isEqualTo("R-1");
        assertThat(ok(shop.owner, get(shop.base + "/requests?filter=ALL"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(2);
        assertThat(ok(shop.employee, get(shop.base + "/requests?filter=MINE"), null)
                        .get("totalElements")
                        .asInt())
                .isEqualTo(2);
        assertThat(ok(shop.owner, get(shop.base + "/requests/summary"), null)
                        .get("needsReview")
                        .asInt())
                .isEqualTo(1);
        assertThat(ok(shop.employee, get(shop.base + "/requests/summary"), null)
                        .get("needsReview")
                        .asInt())
                .isZero();
        assertThat(ok(shop.owner, get(shop.base + "/audit-events?size=50"), null)
                        .toString())
                .contains("RESTOCK_SUBMITTED", "RESTOCK_APPROVED", "RESTOCK_RECEIVED");
        assertThat(rows(
                        "select count(*) from restock_requests r where r.item_id = ? and r.received_quantity <> coalesce((select sum(quantity) from restock_receipts x where x.request_id = r.id), 0)",
                        UUID.fromString(shop.item)))
                .isZero();
    }

    @Test
    void enforcesTransitionsReasonsVersionsAndRoles() throws Exception {
        var shop = shop();
        var first = submit(shop.employee, shop, "5").get("id").asString();
        assertThat(receive(shop.employee, shop, first, delivery("1")).code()).isEqualTo("NOT_APPROVED");
        assertThat(call(
                                shop.employee,
                                post(shop.base + "/requests/" + first + "/approve"),
                                Map.of("expectedVersion", 0))
                        .status())
                .isEqualTo(403);
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/requests/" + first + "/reject"),
                                Map.of("expectedVersion", 0, "reason", " "))
                        .status())
                .isEqualTo(400);
        assertThat(call(shop.owner, post(shop.base + "/requests/" + first + "/approve"), Map.of("expectedVersion", 7))
                        .code())
                .isEqualTo("STALE_VERSION");
        var rejected = ok(
                shop.owner,
                post(shop.base + "/requests/" + first + "/reject"),
                Map.of("expectedVersion", 0, "reason", "Enough in the cooler"));
        assertThat(rejected.get("decisionNote").asString()).isEqualTo("Enough in the cooler");
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/requests/" + first + "/approve"),
                                Map.of(
                                        "expectedVersion",
                                        rejected.get("version").asLong()))
                        .code())
                .isEqualTo("INVALID_TRANSITION");
        assertThat(receive(shop.owner, shop, first, delivery("1")).code()).isEqualTo("INVALID_TRANSITION");

        var mine = submit(shop.employee, shop, "2").get("id").asString();
        var ownersRequest = submit(shop.owner, shop, "2").get("id").asString();
        assertThat(call(
                                shop.employee,
                                post(shop.base + "/requests/" + ownersRequest + "/cancel"),
                                Map.of("expectedVersion", 0))
                        .status())
                .isEqualTo(403);
        assertThat(ok(shop.employee, post(shop.base + "/requests/" + mine + "/cancel"), Map.of("expectedVersion", 0))
                        .get("status")
                        .asString())
                .isEqualTo("CANCELLED");

        var approved = approve(shop, ownersRequest, null);
        assertThat(approved.get("approved").asString()).isEqualTo("2");
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/requests/" + ownersRequest + "/cancel"),
                                Map.of(
                                        "expectedVersion",
                                        approved.get("version").asLong()))
                        .code())
                .isEqualTo("INVALID_TRANSITION");
        assertThat(call(
                                shop.employee,
                                post(shop.base + "/requests/" + ownersRequest + "/close-short"),
                                Map.of(
                                        "expectedVersion",
                                        approved.get("version").asLong(),
                                        "reason",
                                        "x"))
                        .status())
                .isEqualTo(403);
        receive(shop.employee, shop, ownersRequest, delivery("1"));
        var version = current(shop, ownersRequest).get("version").asLong();
        assertThat(call(
                                shop.owner,
                                post(shop.base + "/requests/" + ownersRequest + "/close-short"),
                                Map.of("expectedVersion", version))
                        .status())
                .isEqualTo(400);
        var closed = ok(
                shop.owner,
                post(shop.base + "/requests/" + ownersRequest + "/close-short"),
                Map.of("expectedVersion", version, "reason", "Supplier out of stock"));
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED_SHORT");
        assertThat(closed.get("received").asString()).isEqualTo("1");
        assertThat(receive(shop.owner, shop, ownersRequest, delivery("1")).code())
                .isEqualTo("INVALID_TRANSITION");

        var whole = item(shop.owner, shop.base, "Paper cups", "EACH").get("id").asString();
        assertThat(call(
                                shop.employee,
                                post(shop.base + "/requests"),
                                Map.of("itemId", whole, "locationId", shop.shelf, "quantity", "1.5"))
                        .code())
                .isEqualTo("INVALID_QUANTITY");
        var room = ok(shop.owner, get(shop.base + "/locations?rootsOnly=true"), null)
                .get("items")
                .get(0)
                .get("id")
                .asString();
        assertThat(call(
                                shop.employee,
                                post(shop.base + "/requests"),
                                Map.of("itemId", shop.item, "locationId", room, "quantity", "1"))
                        .code())
                .isEqualTo("LOCATION_NOT_LEAF");
    }

    @Test
    void keepsBusinessesApart() throws Exception {
        var shop = shop();
        var other = shop();
        var id = submit(shop.employee, shop, "3").get("id").asString();
        assertThat(call(other.owner, get(other.base + "/requests/" + id), null).status())
                .isEqualTo(404);
        assertThat(call(other.owner, post(other.base + "/requests/" + id + "/approve"), Map.of("expectedVersion", 0))
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                other.owner,
                                post(other.base + "/requests"),
                                Map.of("itemId", shop.item, "locationId", other.shelf, "quantity", "1"))
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                other.owner,
                                post(other.base + "/requests"),
                                Map.of("itemId", other.item, "locationId", shop.shelf, "quantity", "1"))
                        .status())
                .isEqualTo(404);
        assertThat(call(other.owner, get(shop.base + "/requests"), null).status())
                .isEqualTo(404);
    }

    @Test
    void retriedDeliveriesCountOnce() throws Exception {
        var shop = shop();
        var id = submit(shop.employee, shop, "5").get("id").asString();
        approve(shop, id, null);
        var body = delivery("2");
        assertThat(receive(shop.employee, shop, id, body).status()).isEqualTo(201);
        var retry = receive(shop.employee, shop, id, body);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().get("replayed").asBoolean()).isTrue();
        var changed = new HashMap<>(body);
        changed.put("quantity", "3");
        assertThat(receive(shop.employee, shop, id, changed).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(stock(shop)).isEqualByComparingTo("2");
        assertThat(rows("select count(*) from restock_receipts where request_id=?", UUID.fromString(id)))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveryReplaysTheFirst() throws Exception {
        var shop = shop();
        var id = submit(shop.employee, shop, "5").get("id").asString();
        approve(shop, id, null);
        var body = delivery("5");
        var statuses = holdFirstThen(
                () -> receive(shop.employee, shop, id, body).status(),
                () -> receive(shop.employee, shop, id, body).status());
        assertThat(statuses).containsExactly(201, 200);
        assertThat(stock(shop)).isEqualByComparingTo("5");
        assertThat(rows("select count(*) from restock_receipts where request_id=?", UUID.fromString(id)))
                .isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesCannotExceedTheApprovedQuantity() throws Exception {
        var shop = shop();
        var id = submit(shop.employee, shop, "5").get("id").asString();
        approve(shop, id, null);
        var statuses = holdFirstThen(
                () -> receive(shop.employee, shop, id, delivery("4")).status(),
                () -> receive(shop.owner, shop, id, delivery("3")).status());
        assertThat(statuses).containsExactly(201, 409);
        assertThat(current(shop, id).get("received").asString()).isEqualTo("4");
        assertThat(stock(shop)).isEqualByComparingTo("4");
    }

    @Test
    void failedDeliveryRollsBackStockReceiptAndRequest() throws Exception {
        var shop = shop();
        var id = submit(shop.employee, shop, "5").get("id").asString();
        approve(shop, id, null);
        doThrow(new IllegalStateException("Forced audit failure for rollback verification"))
                .when(audit)
                .record(any(), any(), anyString(), eq("RESTOCK_RECEIVED"), anyString(), any(), anyMap());
        assertThat(receive(shop.employee, shop, id, delivery("2")).status()).isEqualTo(500);
        assertThat(stock(shop)).isEqualByComparingTo("0");
        assertThat(current(shop, id).get("status").asString()).isEqualTo("APPROVED");
        assertThat(rows("select count(*) from restock_receipts where request_id=?", UUID.fromString(id)))
                .isZero();
        assertThat(rows("select count(*) from stock_operations where item_id=?", UUID.fromString(shop.item)))
                .isZero();
    }

    @Test
    void reversingADeliveryReopensTheRequest() throws Exception {
        var shop = shop();
        var id = submit(shop.employee, shop, "5").get("id").asString();
        approve(shop, id, null);
        var received = receive(shop.employee, shop, id, delivery("5")).json();
        assertThat(received.get("status").asString()).isEqualTo("FULFILLED");
        var operation = received.get("receipts").get(0).get("stockOperationId").asString();
        ok(
                shop.owner,
                post(shop.base + "/inventory/operations/" + operation + "/reverse"),
                Map.of("operationKey", UUID.randomUUID().toString()));
        var reopened = current(shop, id);
        assertThat(reopened.get("status").asString()).isEqualTo("APPROVED");
        assertThat(reopened.get("received").asString()).isEqualTo("0");
        assertThat(reopened.get("receipts").get(0).get("reversed").asBoolean()).isTrue();
        assertThat(stock(shop)).isEqualByComparingTo("0");
        assertThat(receive(shop.employee, shop, id, delivery("5"))
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("FULFILLED");
        assertThatThrownBy(() -> jdbc.update("delete from restock_receipts where request_id=?", UUID.fromString(id)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }
}
