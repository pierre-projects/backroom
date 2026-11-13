package com.backroom.inventory;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;
import static com.backroom.inventory.StockOperationType.COUNT;
import static com.backroom.inventory.StockOperationType.OPENING;
import static com.backroom.inventory.StockOperationType.RECEIPT;
import static com.backroom.inventory.StockOperationType.REVERSAL;
import static com.backroom.inventory.StockOperationType.SALE;
import static com.backroom.inventory.StockOperationType.TRANSFER;
import static com.backroom.inventory.StockOperationType.USAGE;
import static com.backroom.inventory.StockOperationType.WASTE;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.web.PageResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every stock balance change. Each command runs in one short transaction:
 * authorize, look up the idempotency key, lock the item (shared), claim the key, lock destination locations (shared)
 * and placements, lock balances in location-id order, validate, then update balances and append movements and audit.
 * Stock commands use row locks rather than the organization-wide lock so staff recording stock don't queue behind each other.
 */
@Service
@Transactional
public class StockService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StockService.class);
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

    StockService(
            ItemRepository items,
            PlacementRepository placements,
            StockBalanceRepository balances,
            StockOperationRepository operations,
            StockMovementRepository movements,
            StockCountRepository counts,
            LocationQueries locations,
            AccessPolicy access,
            AuditService audit,
            StockCommandHooks hooks,
            List<StockReversalListener> reversalListeners) {
        this.items = items;
        this.placements = placements;
        this.balances = balances;
        this.operations = operations;
        this.movements = movements;
        this.counts = counts;
        this.locations = locations;
        this.access = access;
        this.audit = audit;
        this.hooks = hooks;
        this.reversalListeners = reversalListeners;
    }

    // ---------- Commands ----------

    public StockDtos.OperationView receive(UUID orgId, StockDtos.Receipt in) {
        access.require(orgId);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        var quantity = Quantities.positive(item.getUnit(), in.quantity());
        var operation =
                claim(orgId, RECEIPT, item, in.operationKey(), in.fingerprint(), in.reference(), in.note(), null);
        prepareDestination(orgId, item.getId(), in.locationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(RECEIPT, item.getId());
        var movement = apply(operation, locked.get(in.locationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(
                orgId,
                "STOCK_RECEIVED",
                item,
                details(quantity, item, "location", path(labels, in.locationId()), "reference", in.reference()));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView openingBalance(UUID orgId, StockDtos.Opening in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        var quantity = Quantities.positive(item.getUnit(), in.quantity());
        var operation = claim(orgId, OPENING, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        prepareDestination(orgId, item.getId(), in.locationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(OPENING, item.getId());
        if (movements.existsByOrganizationIdAndItemIdAndLocationId(orgId, item.getId(), in.locationId())) {
            throw new ConflictException(
                    "OPENING_ALREADY_RECORDED",
                    "This location already has stock history. Receive or count stock instead.");
        }
        var movement = apply(operation, locked.get(in.locationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(orgId, "STOCK_OPENED", item, details(quantity, item, "location", path(labels, in.locationId())));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView remove(UUID orgId, StockDtos.Removal in) {
        access.require(orgId);
        var type = switch (in.reason()) {
            case WASTE -> WASTE;
            case SALE -> SALE;
            case USAGE -> USAGE;
        };
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.locationId());
        var quantity = Quantities.positive(item.getUnit(), in.quantity());
        var operation = claim(orgId, type, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        var locked = lockBalances(orgId, item.getId(), List.of(in.locationId()));
        hooks.afterBalancesLocked(type, item.getId());
        var movement = apply(operation, locked.get(in.locationId()), quantity.negate());
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(
                orgId,
                switch (type) {
                    case WASTE -> "STOCK_WASTED";
                    case SALE -> "STOCK_SOLD";
                    default -> "STOCK_USED";
                },
                item,
                details(quantity, item, "location", path(labels, in.locationId())));
        return view(operation, List.of(movement), null, false, labels);
    }

    public StockDtos.OperationView transfer(UUID orgId, StockDtos.Transfer in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        if (in.fromLocationId().equals(in.toLocationId())) {
            throw new InvalidRequestException("SAME_LOCATION", "Choose a different destination location.");
        }
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.fromLocationId());
        var quantity = Quantities.positive(item.getUnit(), in.quantity());
        var operation = claim(orgId, TRANSFER, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        prepareDestination(orgId, item.getId(), in.toLocationId());
        var locked = lockBalances(orgId, item.getId(), List.of(in.fromLocationId(), in.toLocationId()));
        hooks.afterBalancesLocked(TRANSFER, item.getId());
        var out = apply(operation, locked.get(in.fromLocationId()), quantity.negate());
        var into = apply(operation, locked.get(in.toLocationId()), quantity);
        var labels = locations.labels(orgId, List.of(in.fromLocationId(), in.toLocationId()));
        record(
                orgId,
                "STOCK_TRANSFERRED",
                item,
                details(
                        quantity,
                        item,
                        "from",
                        path(labels, in.fromLocationId()),
                        "to",
                        path(labels, in.toLocationId())));
        return view(operation, List.of(out, into), null, false, labels);
    }

    public StockDtos.OperationView count(UUID orgId, StockDtos.Count in) {
        access.require(orgId, OWNER, MANAGER);
        var replay = replayIfKnown(orgId, in.operationKey(), in.fingerprint());
        if (replay != null) return replay;
        var item = lockItem(orgId, in.itemId());
        locations.requireExists(orgId, in.locationId());
        var counted = Quantities.nonNegative(item.getUnit(), in.countedQuantity());
        var operation = claim(orgId, COUNT, item, in.operationKey(), in.fingerprint(), null, in.note(), null);
        var balance =
                lockBalances(orgId, item.getId(), List.of(in.locationId())).get(in.locationId());
        hooks.afterBalancesLocked(COUNT, item.getId());
        if (balance == null)
            throw new ConflictException("NOT_PLACED", "Add this location to the item before counting it.");
        if (!Objects.equals(balance.getVersion(), in.expectedBalanceVersion())) {
            throw new ConflictException(
                    "STALE_COUNT",
                    "Stock here changed after you started counting. Check the latest quantity and count again.");
        }
        var recorded = balance.getQuantity();
        var difference = counted.subtract(recorded);
        var changed =
                difference.signum() == 0 ? List.<StockMovement>of() : List.of(apply(operation, balance, difference));
        var observation =
                counts.save(new StockCount(orgId, operation.getId(), item.getId(), in.locationId(), counted, recorded));
        var labels = locations.labels(orgId, List.of(in.locationId()));
        record(
                orgId,
                "STOCK_COUNTED",
                item,
                details(
                        counted,
                        item,
                        "location",
                        path(labels, in.locationId()),
                        "recorded",
                        Quantities.text(recorded),
                        "difference",
                        Quantities.text(difference)));
        return view(operation, changed, observation, false, labels);
    }

    public StockDtos.OperationView reverse(UUID orgId, UUID operationId, StockDtos.Reverse in) {
        access.require(orgId, OWNER, MANAGER);
        var fingerprint = in.fingerprint(operationId);
        var replay = replayIfKnown(orgId, in.operationKey(), fingerprint);
        if (replay != null) return replay;
        var original = operations.findByOrganizationIdAndId(orgId, operationId).orElseThrow(NotFoundException::new);
        if (!original.getType().reversible()) {
            throw new ConflictException(
                    "NOT_REVERSIBLE",
                    original.getType() == COUNT
                            ? "Counts can't be reversed. Count the location again instead."
                            : "A reversal can't be reversed.");
        }
        var item = lockItem(orgId, original.getItemId());
        if (operations.existsByOrganizationIdAndReversesOperationId(orgId, operationId)) {
            throw new ConflictException("ALREADY_REVERSED", "This operation has already been reversed.");
        }
        var operation = claim(orgId, REVERSAL, item, in.operationKey(), fingerprint, null, in.note(), operationId);
        // Other modules (restocking) adjust their records here, before balances are locked.
        reversalListeners.forEach(listener -> listener.onReversal(orgId, operationId));
        var originalMovements =
                movements.findByOrganizationIdAndOperationIdOrderByQuantityChangeAsc(orgId, operationId);
        // Stock that was removed comes back: those locations must still be able to hold it.
        originalMovements.stream()
                .filter(movement -> movement.getQuantityChange().signum() < 0)
                .forEach(movement -> prepareDestination(orgId, item.getId(), movement.getLocationId()));
        var ids = originalMovements.stream()
                .map(movement -> movement.getLocationId())
                .toList();
        var locked = lockBalances(orgId, item.getId(), ids);
        hooks.afterBalancesLocked(REVERSAL, item.getId());
        var applied = new ArrayList<StockMovement>();
        for (var movement : originalMovements) {
            applied.add(apply(
                    operation,
                    locked.get(movement.getLocationId()),
                    movement.getQuantityChange().negate()));
        }
        var labels = locations.labels(orgId, ids);
        var total = originalMovements.stream()
                .map(movement -> movement.getQuantityChange().abs())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        record(
                orgId,
                "STOCK_REVERSED",
                item,
                details(
                        total,
                        item,
                        "reversedType",
                        original.getType().name(),
                        "reversedOperationId",
                        operationId.toString()));
        return view(operation, applied, null, false, labels);
    }

    /** Called after a concurrent duplicate lost the race on the idempotency key and its transaction rolled back. */
    @Transactional(readOnly = true)
    public StockDtos.OperationView replayAfterConflict(UUID orgId, UUID operationKey, String fingerprint) {
        access.require(orgId);
        var existing = operations
                .findByOrganizationIdAndOperationKey(orgId, operationKey)
                .orElseThrow(() -> new ConflictException("CONCURRENT_CHANGE", "This record changed. Try again."));
        return replay(orgId, existing, fingerprint);
    }

    // ---------- Reads ----------

    @Transactional(readOnly = true)
    public List<StockDtos.BalanceView> balances(UUID orgId, UUID itemId) {
        access.require(orgId);
        items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(NotFoundException::new);
        var active = placements.activeLocations(orgId, itemId);
        var rows = balances.findByOrganizationIdAndItemId(orgId, itemId).stream()
                .filter(balance -> active.contains(balance.getLocationId())
                        || balance.getQuantity().signum() > 0)
                .toList();
        var history = movements.locationsWithHistory(orgId, itemId);
        var labels = locations.labels(
                orgId, rows.stream().map(balance -> balance.getLocationId()).toList());
        var minimums = new HashMap<UUID, BigDecimal>();
        placements.findByOrganizationIdAndItemIdAndActiveTrue(orgId, itemId).forEach(placement -> {
            if (placement.getMinimumQuantity() != null)
                minimums.put(placement.getLocationId(), placement.getMinimumQuantity());
        });
        return rows.stream()
                .map(balance -> {
                    var label = labels.get(balance.getLocationId());
                    var minimum = minimums.get(balance.getLocationId());
                    return new StockDtos.BalanceView(
                            itemId,
                            balance.getLocationId(),
                            label == null ? null : label.name(),
                            label == null ? null : label.parentName(),
                            Quantities.text(balance.getQuantity()),
                            balance.getVersion(),
                            history.contains(balance.getLocationId()),
                            minimum == null ? null : Quantities.text(minimum),
                            minimum != null && balance.getQuantity().compareTo(minimum) < 0);
                })
                .sorted(Comparator.comparing((StockDtos.BalanceView view) -> Objects.toString(view.parentName(), ""))
                        .thenComparing(view -> Objects.toString(view.locationName(), ""))
                        .thenComparing(StockDtos.BalanceView::locationId))
                .toList();
    }

    /** Every slot below its minimum, sorted by item name. */
    @Transactional(readOnly = true)
    public PageResponse<StockDtos.LowStockView> lowStock(UUID orgId, UUID locationId, int page, int size) {
        access.require(orgId);
        if (locationId != null) locations.requireExists(orgId, locationId);
        var result = balances.lowStock(orgId, locationId, PageResponse.request(page, size, Sort.unsorted()));
        var rows = result.getContent();
        var itemIds =
                rows.stream().map(row -> ((StockBalance) row[0]).getItemId()).toList();
        var names = new HashMap<UUID, Item>();
        if (!itemIds.isEmpty()) items.findAllById(itemIds).forEach(item -> names.put(item.getId(), item));
        var labels = locations.labels(
                orgId,
                rows.stream()
                        .map(row -> ((StockBalance) row[0]).getLocationId())
                        .toList());
        return PageResponse.from(result.map(row -> {
            var balance = (StockBalance) row[0];
            var minimum = (BigDecimal) row[1];
            var item = names.get(balance.getItemId());
            var label = labels.get(balance.getLocationId());
            return new StockDtos.LowStockView(
                    balance.getItemId(),
                    item.getName(),
                    item.getSku(),
                    item.getUnit(),
                    balance.getLocationId(),
                    label == null ? null : label.name(),
                    label == null ? null : label.parentName(),
                    Quantities.text(balance.getQuantity()),
                    Quantities.text(minimum),
                    Quantities.text(minimum.subtract(balance.getQuantity())));
        }));
    }

    @Transactional(readOnly = true)
    public PageResponse<StockDtos.MovementView> movements(
            UUID orgId, UUID itemId, UUID locationId, int page, int size) {
        access.require(orgId);
        if (itemId != null) items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(NotFoundException::new);
        if (locationId != null) locations.requireExists(orgId, locationId);
        var result = movements.search(
                orgId,
                itemId,
                locationId,
                PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var operationIds =
                result.stream().map(movement -> movement.getOperationId()).collect(java.util.stream.Collectors.toSet());
        var byId = new HashMap<UUID, StockOperation>();
        if (!operationIds.isEmpty())
            operations
                    .findByOrganizationIdAndIdIn(orgId, operationIds)
                    .forEach(operation -> byId.put(operation.getId(), operation));
        var reversed = operationIds.isEmpty() ? Set.<UUID>of() : operations.reversedAmong(orgId, operationIds);
        var labels = locations.labels(
                orgId, result.stream().map(movement -> movement.getLocationId()).toList());
        return PageResponse.from(result.map(movement -> movementView(
                movement, byId.get(movement.getOperationId()), labels, reversed.contains(movement.getOperationId()))));
    }

    @Transactional(readOnly = true)
    public StockDtos.OperationView operation(UUID orgId, UUID operationId) {
        access.require(orgId);
        var operation = operations.findByOrganizationIdAndId(orgId, operationId).orElseThrow(NotFoundException::new);
        return fullView(orgId, operation, false);
    }

    /** The operation a client key produced in this organization, if any. Callers authorize. */
    @Transactional(readOnly = true)
    public Optional<UUID> operationIdForKey(UUID orgId, UUID operationKey) {
        return operations
                .findByOrganizationIdAndOperationKey(orgId, operationKey)
                .map(StockOperation::getId);
    }

    /** Which of these operations have since been reversed. Callers authorize. */
    @Transactional(readOnly = true)
    public Set<UUID> reversedAmong(UUID orgId, Collection<UUID> operationIds) {
        return operationIds.isEmpty() ? Set.of() : operations.reversedAmong(orgId, operationIds);
    }

    // ---------- Steps ----------

    private StockDtos.OperationView replayIfKnown(UUID orgId, UUID key, String fingerprint) {
        return operations
                .findByOrganizationIdAndOperationKey(orgId, key)
                .map(existing -> replay(orgId, existing, fingerprint))
                .orElse(null);
    }

    private StockDtos.OperationView replay(UUID orgId, StockOperation existing, String fingerprint) {
        var actor = access.actor();
        if (!existing.getActorId().equals(actor.userId())
                || !existing.getPayloadHash().equals(hash(fingerprint))) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REUSED", "This operation key was already used for a different request.");
        }
        log.info(
                "Stock operation replayed: type={} operationKey={} organizationId={}",
                existing.getType(),
                existing.getOperationKey(),
                orgId);
        return fullView(orgId, existing, true);
    }

    /** Shared row lock: the item can't be archived or have its unit changed while stock is recorded against it. */
    private Item lockItem(UUID orgId, UUID itemId) {
        var item = items.lockForShare(orgId, itemId).orElseThrow(NotFoundException::new);
        if (item.isArchived()) throw new ConflictException("ARCHIVED", "Archived items can't change stock.");
        return item;
    }

    private StockOperation claim(
            UUID orgId,
            StockOperationType type,
            Item item,
            UUID key,
            String fingerprint,
            String reference,
            String note,
            UUID reverses) {
        var actor = access.actor();
        var claimed = operations.saveAndFlush(new StockOperation(
                orgId,
                type,
                item.getId(),
                actor.userId(),
                actor.displayName(),
                key,
                hash(fingerprint),
                blankToNull(reference),
                blankToNull(note),
                reverses));
        // Ids only: never names, notes, references or quantities. A rollback after this line is logged as the request's
        // failure.
        log.info("Stock operation recorded: type={} operationKey={} organizationId={}", type, key, orgId);
        return claimed;
    }

    /** A destination must be an active leaf. Creates or reactivates the placement and its balance, holding their row locks. */
    private void prepareDestination(UUID orgId, UUID itemId, UUID locationId) {
        locations.lockActiveLeafForShare(orgId, locationId);
        placements.insertIfMissing(orgId, itemId, locationId);
        var placement = placements.lockSlot(orgId, itemId, locationId).orElseThrow(NotFoundException::new);
        if (!placement.isActive()) {
            placement.activate();
            placements.flush();
        }
        balances.insertIfMissing(orgId, itemId, locationId);
    }

    /** Locks balances in ascending location-id order so opposing transfers can't deadlock. Missing slots map to null. */
    private Map<UUID, StockBalance> lockBalances(UUID orgId, UUID itemId, Collection<UUID> locationIds) {
        var locked = new HashMap<UUID, StockBalance>();
        for (var locationId : new TreeSet<>(locationIds))
            locked.put(locationId, balances.lockSlot(orgId, itemId, locationId).orElse(null));
        return locked;
    }

    private StockMovement apply(StockOperation operation, StockBalance balance, BigDecimal change) {
        if (balance == null) throw insufficient();
        var after = balance.getQuantity().add(change);
        if (after.signum() < 0) throw insufficient();
        balance.changeQuantity(after);
        return movements.save(new StockMovement(
                operation.getOrganizationId(),
                operation.getId(),
                operation.getItemId(),
                balance.getLocationId(),
                change,
                after));
    }

    private static ConflictException insufficient() {
        return new ConflictException("INSUFFICIENT_STOCK", "There isn't enough stock at this location for that.");
    }

    // ---------- Views and audit ----------

    private StockDtos.OperationView fullView(UUID orgId, StockOperation operation, boolean replayed) {
        var applied = movements.findByOrganizationIdAndOperationIdOrderByQuantityChangeAsc(orgId, operation.getId());
        var count = operation.getType() == COUNT
                ? counts.findByOrganizationIdAndOperationId(orgId, operation.getId())
                        .orElse(null)
                : null;
        var ids = new ArrayList<UUID>(
                applied.stream().map(movement -> movement.getLocationId()).toList());
        return view(operation, applied, count, replayed, locations.labels(orgId, ids));
    }

    private StockDtos.OperationView view(
            StockOperation operation,
            List<StockMovement> applied,
            StockCount count,
            boolean replayed,
            Map<UUID, LocationQueries.Label> labels) {
        var reversed = operations.existsByOrganizationIdAndReversesOperationId(
                operation.getOrganizationId(), operation.getId());
        var countView = count == null
                ? null
                : new StockDtos.CountResult(
                        Quantities.text(count.getCountedQuantity()),
                        Quantities.text(count.getRecordedQuantity()),
                        Quantities.text(count.getCountedQuantity().subtract(count.getRecordedQuantity())));
        return new StockDtos.OperationView(
                operation.getId(),
                operation.getType(),
                operation.getItemId(),
                operation.getActorName(),
                operation.getCreatedAt(),
                operation.getReversesOperationId(),
                applied.stream()
                        .map(movement -> movementView(movement, operation, labels, reversed))
                        .toList(),
                countView,
                replayed);
    }

    private StockDtos.MovementView movementView(
            StockMovement movement,
            StockOperation operation,
            Map<UUID, LocationQueries.Label> labels,
            boolean reversed) {
        var label = labels.get(movement.getLocationId());
        return new StockDtos.MovementView(
                movement.getId(),
                movement.getOperationId(),
                operation == null ? null : operation.getType(),
                movement.getItemId(),
                movement.getLocationId(),
                label == null ? null : label.name(),
                label == null ? null : label.parentName(),
                Quantities.text(movement.getQuantityChange()),
                Quantities.text(movement.getBalanceAfter()),
                operation == null ? null : operation.getActorName(),
                movement.getCreatedAt(),
                operation == null ? null : operation.getReference(),
                operation == null ? null : operation.getNote(),
                operation == null ? null : operation.getReversesOperationId(),
                reversed);
    }

    private static String path(Map<UUID, LocationQueries.Label> labels, UUID locationId) {
        var label = labels.get(locationId);
        return label == null ? "" : label.path();
    }

    private static Map<String, String> details(BigDecimal quantity, Item item, String... pairs) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("name", item.getName());
        metadata.put("quantity", Quantities.text(quantity));
        metadata.put("unit", item.getUnit().name());
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            if (pairs[index + 1] != null && !pairs[index + 1].isBlank()) metadata.put(pairs[index], pairs[index + 1]);
        }
        return metadata;
    }

    private void record(UUID orgId, String action, Item item, Map<String, String> metadata) {
        var actor = access.actor();
        audit.record(orgId, actor.userId(), actor.displayName(), action, "ITEM", item.getId(), metadata);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String hash(String fingerprint) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
