package com.backroom.inventory;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.ApiException;
import com.backroom.shared.PageResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.backroom.access.Role.*;
import static com.backroom.inventory.StockOperationType.*;

/**
 * Owns every stock balance change. Each command runs in one short transaction:
 * authorize, look up the idempotency key, lock the item (shared), claim the key, lock destination locations (shared)
 * and placements, lock balances in location-id order, validate, then update balances and append movements and audit.
 * Stock commands use row locks rather than the organization-wide lock so staff recording stock don't queue behind each other.
 */
@Service
@Transactional
public class StockService {
    private final ItemRepository items;
    private final PlacementRepository placements;
    private final StockBalanceRepository balances;
    private final StockOperationRepository operations;
    private final StockMovementRepository movements;
    private final StockCountRepository counts;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final AuditService audit;
    private final StockCommandHooks hooks;
    private final List<StockReversalListener> reversalListeners;

    StockService(ItemRepository items, PlacementRepository placements, StockBalanceRepository balances,
                 StockOperationRepository operations, StockMovementRepository movements, StockCountRepository counts,
                 LocationQueries locations, AccessPolicy access, AuditService audit, StockCommandHooks hooks,
                 List<StockReversalListener> reversalListeners) {
        this.items=items; this.placements=placements; this.balances=balances; this.operations=operations;
        this.movements=movements; this.counts=counts; this.locations=locations; this.access=access; this.audit=audit; this.hooks=hooks;
        this.reversalListeners=reversalListeners;
    }

    // ---------- Commands ----------

    public StockDtos.OperationView receive(UUID orgId, StockDtos.Receipt in) {
        access.require(orgId);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        var quantity = Quantities.positive(item.unit, in.quantity());
        var operation = claim(orgId, RECEIPT, item, in.operationKey(), in.fingerprint(), in.reference(), in.note(), null);
        prepareDestination(orgId, item.getId(), in.locationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(RECEIPT, item.getId());
        var movement = apply(operation, locked.get(in.locationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(orgId, "STOCK_RECEIVED", item, details(quantity, item, "location", path(labels, in.locationId()), "reference", in.reference()));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView openingBalance(UUID orgId, StockDtos.Opening in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        var quantity = Quantities.positive(item.unit, in.quantity());
        var operation = claim(orgId, OPENING, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        prepareDestination(orgId, item.getId(), in.locationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(OPENING, item.getId());
        if (movements.existsByOrganizationIdAndItemIdAndLocationId(orgId, item.getId(), in.locationId())) {
            throw ApiException.conflict("OPENING_ALREADY_RECORDED", "This location already has stock history. Receive or count stock instead.");
        }
        var movement = apply(operation, locked.get(in.locationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(orgId, "STOCK_OPENED", item, details(quantity, item, "location", path(labels, in.locationId())));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView remove(UUID orgId, StockDtos.Removal in) {
        access.require(orgId);
        var type = in.reason() == StockDtos.RemovalReason.WASTE ? WASTE : USAGE;
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.locationId());
        var quantity = Quantities.positive(item.unit, in.quantity());
        var operation = claim(orgId, type, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(type, item.getId());
        var movement = apply(operation, locked.get(in.locationId()), quantity.negate());
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(orgId, type == WASTE ? "STOCK_WASTED" : "STOCK_USED", item, details(quantity, item, "location", path(labels, in.locationId())));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView transfer(UUID orgId, StockDtos.Transfer in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        if (in.fromLocationId().equals(in.toLocationId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_LOCATION", "Choose a different destination location.");
        }
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.fromLocationId());
        var quantity = Quantities.positive(item.unit, in.quantity());
        var operation = claim(orgId, TRANSFER, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        prepareDestination(orgId, item.getId(), in.toLocationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.fromLocationId(), in.toLocationId()));
        hooks.afterBalancesLocked(TRANSFER, item.getId());
        var out = apply(operation, locked.get(in.fromLocationId()), quantity.negate());
        var into = apply(operation, locked.get(in.toLocationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.fromLocationId(), in.toLocationId()));
        record(orgId, "STOCK_TRANSFERRED", item, details(quantity, item,
                "from", path(labels, in.fromLocationId()), "to", path(labels, in.toLocationId())));
        return view(operation, List.of(out, into), null, false, labels);
    }

    public StockDtos.OperationView count(UUID orgId, StockDtos.Count in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.locationId());
        var counted = Quantities.nonNegative(item.unit, in.countedQuantity());
        var operation = claim(orgId, COUNT, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        var balance = lockBalances(orgId, item.getId(), List.of(in.locationId())).get(in.locationId());
        hooks.afterBalancesLocked(COUNT, item.getId());
        if (balance == null) throw ApiException.conflict("NOT_PLACED", "Add this location to the item before counting it.");
        if (!Objects.equals(balance.getVersion(), in.expectedBalanceVersion())) {
            throw ApiException.conflict("STALE_COUNT", "Stock here changed after you started counting. Check the latest quantity and count again.");
        }
        var recorded = balance.quantity;
        var difference = counted.subtract(recorded);
        var changed = difference.signum() == 0 ? List.<StockMovement>of() : List.of(apply(operation, balance, difference));
        var observation = counts.save(new StockCount(orgId, operation.getId(), item.getId(), in.locationId(), counted, recorded));
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(orgId, "STOCK_COUNTED", item, details(counted, item, "location", path(labels, in.locationId()),
                "recorded", Quantities.text(recorded), "difference", Quantities.text(difference)));
        return view(operation, changed, observation, false, labels);
    }

    public StockDtos.OperationView reverse(UUID orgId, UUID operationId, StockDtos.Reverse in) {
        access.require(orgId, OWNER, MANAGER);
        var fingerprint = in.fingerprint(operationId);
        var replay = replayIfKnown(orgId, in.operationKey(), fingerprint);
        if (replay != null) return replay;
        var original = operations.findByOrganizationIdAndId(orgId, operationId).orElseThrow(ApiException::notFound);
        if (!original.type.reversible()) {
            throw ApiException.conflict("NOT_REVERSIBLE", original.type == COUNT
                    ? "Counts can't be reversed. Count the location again instead." : "A reversal can't be reversed.");
        }
        var item = lockItem(orgId, original.itemId);
        if (operations.existsByOrganizationIdAndReversesOperationId(orgId, operationId)) {
            throw ApiException.conflict("ALREADY_REVERSED", "This operation has already been reversed.");
        }
        var operation = claim(orgId, REVERSAL, item, in.operationKey(), fingerprint, null, in.note(), operationId);
        // Other modules (restocking) adjust their records here, before balances are locked.
        reversalListeners.forEach(listener -> listener.onReversal(orgId, operationId));
        var originalMovements = movements.findByOrganizationIdAndOperationIdOrderByQuantityChangeAsc(orgId, operationId);
        // Stock that was removed comes back: those locations must still be able to hold it.
        originalMovements.stream().filter(movement -> movement.quantityChange.signum() < 0)
                .forEach(movement -> prepareDestination(orgId, item.getId(), movement.locationId));
        var ids = originalMovements.stream().map(movement -> movement.locationId).toList();
        var locked = lockBalances(orgId, item.getId(), ids);
        hooks.afterBalancesLocked(REVERSAL, item.getId());
        var applied = new ArrayList<StockMovement>();
        for (var movement : originalMovements) {
            applied.add(apply(operation, locked.get(movement.locationId), movement.quantityChange.negate()));
        }
        var labels = locations.labels(orgId, ids);
        var total = originalMovements.stream().map(movement -> movement.quantityChange.abs()).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        record(orgId, "STOCK_REVERSED", item, details(total, item, "reversedType", original.type.name(),
                "reversedOperationId", operationId.toString()));
        return view(operation, applied, null, false, labels);
    }

    /** Called after a concurrent duplicate lost the race on the idempotency key and its transaction rolled back. */
    @Transactional(readOnly=true)
    public StockDtos.OperationView replayAfterConflict(UUID orgId, UUID operationKey, String fingerprint) {
        access.require(orgId);
        var existing = operations.findByOrganizationIdAndOperationKey(orgId, operationKey)
                .orElseThrow(() -> ApiException.conflict("CONCURRENT_CHANGE", "This record changed. Try again."));
        return replay(orgId, existing, fingerprint);
    }

    // ---------- Reads ----------

    @Transactional(readOnly=true)
    public List<StockDtos.BalanceView> balances(UUID orgId, UUID itemId) {
        access.require(orgId);
        items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(ApiException::notFound);
        var active = placements.activeLocations(orgId, itemId);
        var rows = balances.findByOrganizationIdAndItemId(orgId, itemId).stream()
                .filter(balance -> active.contains(balance.locationId) || balance.quantity.signum() > 0).toList();
        var history = movements.locationsWithHistory(orgId, itemId);
        var labels = locations.labels(orgId, rows.stream().map(balance -> balance.locationId).toList());
        var minimums = new HashMap<UUID, BigDecimal>();
        placements.findByOrganizationIdAndItemIdAndActiveTrue(orgId, itemId).forEach(placement -> {
            if (placement.minimumQuantity != null) minimums.put(placement.locationId, placement.minimumQuantity);
        });
        return rows.stream().map(balance -> {
            var label = labels.get(balance.locationId);
            var minimum = minimums.get(balance.locationId);
            return new StockDtos.BalanceView(itemId, balance.locationId, label == null ? null : label.name(),
                    label == null ? null : label.parentName(), Quantities.text(balance.quantity), balance.getVersion(),
                    history.contains(balance.locationId), minimum == null ? null : Quantities.text(minimum),
                    minimum != null && balance.quantity.compareTo(minimum) < 0);
        }).sorted(Comparator.comparing((StockDtos.BalanceView view) -> Objects.toString(view.parentName(), ""))
                .thenComparing(view -> Objects.toString(view.locationName(), "")).thenComparing(StockDtos.BalanceView::locationId)).toList();
    }

    /** Every slot below its minimum, sorted by item name. */
    @Transactional(readOnly=true)
    public PageResponse<StockDtos.LowStockView> lowStock(UUID orgId, UUID locationId, int page, int size) {
        access.require(orgId);
        if (locationId != null) locations.requireExists(orgId, locationId);
        var result = balances.lowStock(orgId, locationId, PageResponse.request(page, size, Sort.unsorted()));
        var rows = result.getContent();
        var itemIds = rows.stream().map(row -> ((StockBalance) row[0]).itemId).toList();
        var names = new HashMap<UUID, Item>();
        if (!itemIds.isEmpty()) items.findAllById(itemIds).forEach(item -> names.put(item.getId(), item));
        var labels = locations.labels(orgId, rows.stream().map(row -> ((StockBalance) row[0]).locationId).toList());
        return PageResponse.from(result.map(row -> {
            var balance = (StockBalance) row[0];
            var minimum = (BigDecimal) row[1];
            var item = names.get(balance.itemId);
            var label = labels.get(balance.locationId);
            return new StockDtos.LowStockView(balance.itemId, item.name, item.sku, item.unit, balance.locationId,
                    label == null ? null : label.name(), label == null ? null : label.parentName(),
                    Quantities.text(balance.quantity), Quantities.text(minimum), Quantities.text(minimum.subtract(balance.quantity)));
        }));
    }

    @Transactional(readOnly=true)
    public PageResponse<StockDtos.MovementView> movements(UUID orgId, UUID itemId, UUID locationId, int page, int size) {
        access.require(orgId);
        if (itemId != null) items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(ApiException::notFound);
        if (locationId != null) locations.requireExists(orgId, locationId);
        var result = movements.search(orgId, itemId, locationId, PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var operationIds = result.stream().map(movement -> movement.operationId).collect(java.util.stream.Collectors.toSet());
        var byId = new HashMap<UUID, StockOperation>();
        if (!operationIds.isEmpty()) operations.findByOrganizationIdAndIdIn(orgId, operationIds).forEach(operation -> byId.put(operation.getId(), operation));
        var reversed = operationIds.isEmpty() ? Set.<UUID>of() : operations.reversedAmong(orgId, operationIds);
        var labels = locations.labels(orgId, result.stream().map(movement -> movement.locationId).toList());
        return PageResponse.from(result.map(movement -> movementView(movement, byId.get(movement.operationId), labels, reversed.contains(movement.operationId))));
    }

    @Transactional(readOnly=true)
    public StockDtos.OperationView operation(UUID orgId, UUID operationId) {
        access.require(orgId);
        var operation = operations.findByOrganizationIdAndId(orgId, operationId).orElseThrow(ApiException::notFound);
        return fullView(orgId, operation, false);
    }

    /** The operation a client key produced in this organization, if any. Callers authorize. */
    @Transactional(readOnly=true)
    public Optional<UUID> operationIdForKey(UUID orgId, UUID operationKey) {
        return operations.findByOrganizationIdAndOperationKey(orgId, operationKey).map(StockOperation::getId);
    }

    /** Which of these operations have since been reversed. Callers authorize. */
    @Transactional(readOnly=true)
    public Set<UUID> reversedAmong(UUID orgId, Collection<UUID> operationIds) {
        return operationIds.isEmpty() ? Set.of() : operations.reversedAmong(orgId, operationIds);
    }

    // ---------- Steps ----------

    private StockDtos.OperationView replayIfKnown(UUID orgId, UUID key, String fingerprint) {
        return operations.findByOrganizationIdAndOperationKey(orgId, key).map(existing -> replay(orgId, existing, fingerprint)).orElse(null);
    }

    private StockDtos.OperationView replay(UUID orgId, StockOperation existing, String fingerprint) {
        var actor = access.actor();
        if (!existing.actorId.equals(actor.userId()) || !existing.payloadHash.equals(hash(fingerprint))) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This operation key was already used for a different request.");
        }
        return fullView(orgId, existing, true);
    }

    /** Shared row lock: the item can't be archived or have its unit changed while stock is recorded against it. */
    private Item lockItem(UUID orgId, UUID itemId) {
        var item = items.lockForShare(orgId, itemId).orElseThrow(ApiException::notFound);
        if (item.archived) throw ApiException.conflict("ARCHIVED", "Archived items can't change stock.");
        return item;
    }

    private StockOperation claim(UUID orgId, StockOperationType type, Item item, UUID key, String fingerprint,
                                 String reference, String note, UUID reverses) {
        var actor = access.actor();
        return operations.saveAndFlush(new StockOperation(orgId, type, item.getId(), actor.userId(), actor.displayName(), key,
                hash(fingerprint), blankToNull(reference), blankToNull(note), reverses));
    }

    /** A destination must be an active leaf. Creates or reactivates the placement and its balance, holding their row locks. */
    private void prepareDestination(UUID orgId, UUID itemId, UUID locationId) {
        locations.lockActiveLeafForShare(orgId, locationId);
        placements.insertIfMissing(orgId, itemId, locationId);
        var placement = placements.lockSlot(orgId, itemId, locationId).orElseThrow(ApiException::notFound);
        if (!placement.active) { placement.active = true; placements.flush(); }
        balances.insertIfMissing(orgId, itemId, locationId);
    }

    /** Locks balances in ascending location-id order so opposing transfers can't deadlock. Missing slots map to null. */
    private Map<UUID, StockBalance> lockBalances(UUID orgId, UUID itemId, Collection<UUID> locationIds) {
        var locked = new HashMap<UUID, StockBalance>();
        for (var locationId : new TreeSet<>(locationIds)) locked.put(locationId, balances.lockSlot(orgId, itemId, locationId).orElse(null));
        return locked;
    }

    private StockMovement apply(StockOperation operation, StockBalance balance, BigDecimal change) {
        if (balance == null) throw insufficient();
        var after = balance.quantity.add(change);
        if (after.signum() < 0) throw insufficient();
        balance.quantity = after;
        balance.updatedAt = Instant.now();
        return movements.save(new StockMovement(operation.getOrganizationId(), operation.getId(), operation.itemId, balance.locationId, change, after));
    }

    private static ApiException insufficient() {
        return ApiException.conflict("INSUFFICIENT_STOCK", "There isn't enough stock at this location for that.");
    }

    // ---------- Views and audit ----------

    private StockDtos.OperationView fullView(UUID orgId, StockOperation operation, boolean replayed) {
        var applied = movements.findByOrganizationIdAndOperationIdOrderByQuantityChangeAsc(orgId, operation.getId());
        var count = operation.type == COUNT ? counts.findByOrganizationIdAndOperationId(orgId, operation.getId()).orElse(null) : null;
        var ids = new ArrayList<UUID>(applied.stream().map(movement -> movement.locationId).toList());
        return view(operation, applied, count, replayed, locations.labels(orgId, ids));
    }

    private StockDtos.OperationView view(StockOperation operation, List<StockMovement> applied, StockCount count, boolean replayed,
                                         Map<UUID, LocationQueries.Label> labels) {
        var reversed = operations.existsByOrganizationIdAndReversesOperationId(operation.getOrganizationId(), operation.getId());
        var countView = count == null ? null : new StockDtos.CountResult(Quantities.text(count.countedQuantity),
                Quantities.text(count.recordedQuantity), Quantities.text(count.countedQuantity.subtract(count.recordedQuantity)));
        return new StockDtos.OperationView(operation.getId(), operation.type, operation.itemId, operation.actorName,
                operation.getCreatedAt(), operation.reversesOperationId,
                applied.stream().map(movement -> movementView(movement, operation, labels, reversed)).toList(), countView, replayed);
    }

    private StockDtos.MovementView movementView(StockMovement movement, StockOperation operation, Map<UUID, LocationQueries.Label> labels, boolean reversed) {
        var label = labels.get(movement.locationId);
        return new StockDtos.MovementView(movement.getId(), movement.operationId, operation == null ? null : operation.type,
                movement.itemId, movement.locationId, label == null ? null : label.name(), label == null ? null : label.parentName(),
                Quantities.text(movement.quantityChange), Quantities.text(movement.balanceAfter),
                operation == null ? null : operation.actorName, movement.getCreatedAt(),
                operation == null ? null : operation.reference, operation == null ? null : operation.note,
                operation == null ? null : operation.reversesOperationId, reversed);
    }

    private static String path(Map<UUID, LocationQueries.Label> labels, UUID locationId) {
        var label = labels.get(locationId);
        return label == null ? "" : label.path();
    }

    private static Map<String, String> details(BigDecimal quantity, Item item, String... pairs) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("name", item.name);
        metadata.put("quantity", Quantities.text(quantity));
        metadata.put("unit", item.unit.name());
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null && !pairs[index + 1].isBlank()) metadata.put(pairs[index], pairs[index + 1]);
        }
        return metadata;
    }

    private void record(UUID orgId, String action, Item item, Map<String, String> metadata) {
        var actor = access.actor();
        audit.record(orgId, actor.userId(), actor.displayName(), action, "ITEM", item.getId(), metadata);
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    static String hash(String fingerprint) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
