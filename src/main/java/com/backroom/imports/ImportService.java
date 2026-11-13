package com.backroom.imports;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.inventory.CatalogDtos;
import com.backroom.inventory.CatalogService;
import com.backroom.inventory.ItemQueries;
import com.backroom.inventory.StockDtos;
import com.backroom.inventory.StockService;
import com.backroom.locations.LocationContentsCounter;
import com.backroom.locations.LocationDtos;
import com.backroom.locations.LocationQueries;
import com.backroom.locations.LocationService;
import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV import of items with their places, starting quantities and minimums. Preview and apply run the same planner;
 * apply re-plans against current data and then writes only through the owning modules' services, in one transaction,
 * so every rule, lock and audit event that applies to typing the data in also applies here.
 */
@Service
public class ImportService {
    public static final int MAX_BYTES = 1024 * 1024;

    public record PreviewRow(
            int line,
            String item,
            String sku,
            String unit,
            String location,
            String quantity,
            String minimum,
            String status,
            List<String> messages) {}

    public record Summary(
            int rows,
            int newItems,
            int duplicates,
            int possibleDuplicates,
            int errors,
            int placements,
            int quantities,
            int minimums,
            List<String> locationsToCreate) {}

    public record Preview(Summary summary, List<PreviewRow> rows) {}

    public record ApplyInput(UUID importKey, String fileName, String csv, List<Integer> include) {}

    public record Result(
            boolean replayed,
            int rows,
            int itemsCreated,
            int locationsCreated,
            int placements,
            int quantities,
            int minimums,
            int skipped,
            int errors) {}

    private final AccessPolicy access;
    private final AuditService audit;
    private final ItemQueries items;
    private final LocationQueries locationQueries;
    private final LocationContentsCounter contents;
    private final LocationService locations;
    private final CatalogService catalog;
    private final StockService stock;
    private final ImportBatchRepository batches;

    ImportService(
            AccessPolicy access,
            AuditService audit,
            ItemQueries items,
            LocationQueries locationQueries,
            LocationContentsCounter contents,
            LocationService locations,
            CatalogService catalog,
            StockService stock,
            ImportBatchRepository batches) {
        this.access = access;
        this.audit = audit;
        this.items = items;
        this.locationQueries = locationQueries;
        this.contents = contents;
        this.locations = locations;
        this.catalog = catalog;
        this.stock = stock;
        this.batches = batches;
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID orgId, String csv) {
        access.require(orgId, OWNER, MANAGER);
        var plan = plan(orgId, csv, Set.of());
        return new Preview(
                summary(plan),
                plan.rows().stream()
                        .map(row -> new PreviewRow(
                                row.line(),
                                row.item(),
                                row.sku(),
                                row.unit(),
                                row.location(),
                                row.quantity(),
                                row.minimum(),
                                row.status().name(),
                                row.messages()))
                        .toList());
    }

    @Transactional
    public Result apply(UUID orgId, ApplyInput input) {
        // The organization lock serializes imports in a business, so a retry waits for the original and then replays
        // it.
        access.lock(orgId, OWNER, MANAGER);
        if (input.importKey() == null) throw new InvalidRequestException("An import key is required.");
        var csv = input.csv() == null ? "" : input.csv();
        if (csv.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
        var include = new TreeSet<>(input.include() == null ? List.<Integer>of() : input.include());
        var fileName = input.fileName() == null || input.fileName().isBlank()
                ? null
                : input.fileName().strip();
        if (fileName != null && fileName.length() > 200) fileName = fileName.substring(0, 200);
        var hash = sha256(csv + "\n#include " + include);
        var previous = batches.findByOrganizationIdAndImportKey(orgId, input.importKey());
        if (previous.isPresent()) {
            if (!previous.get().fileSha256.equals(hash)) {
                throw new ConflictException(
                        "IMPORT_KEY_REUSED",
                        "This import was already applied with a different file. Start a new import.");
            }
            return result(previous.get(), true);
        }

        var plan = plan(orgId, csv, include);
        var created = new HashMap<String, UUID>();
        for (var location : plan.locations()) {
            var parentId = location.parentId() != null
                    ? location.parentId()
                    : location.parentKey() == null ? null : created.get(location.parentKey());
            created.put(
                    location.key(),
                    locations
                            .create(orgId, new LocationDtos.Input(location.name(), location.type(), parentId))
                            .id());
        }
        var itemIds = new HashMap<String, UUID>();
        for (var item : plan.items())
            itemIds.put(
                    item.key(),
                    catalog.create(orgId, new CatalogDtos.Input(item.name(), item.sku(), item.unit()))
                            .id());
        int quantities = 0, minimums = 0;
        for (var slot : plan.slots()) {
            var itemId = itemIds.get(slot.itemKey());
            var locationId = slot.locationId() != null ? slot.locationId() : created.get(slot.locationKey());
            if (slot.quantity() != null) {
                var key = UUID.nameUUIDFromBytes(
                        (input.importKey() + ":" + slot.line()).getBytes(StandardCharsets.UTF_8));
                stock.openingBalance(
                        orgId,
                        new StockDtos.Opening(
                                key,
                                itemId,
                                locationId,
                                slot.quantity(),
                                fileName == null ? "Imported" : "Imported from " + fileName));
                quantities++;
            } else {
                catalog.place(orgId, itemId, locationId);
            }
            if (slot.minimum() != null) {
                catalog.setMinimum(orgId, itemId, locationId, slot.minimum());
                minimums++;
            }
        }

        var batch = new ImportBatch(orgId, input.importKey());
        var actor = access.actor();
        batch.fileSha256 = hash;
        batch.fileName = fileName;
        batch.createdBy = actor.userId();
        batch.rows = plan.rows().size();
        batch.itemsCreated = plan.items().size();
        batch.locationsCreated = plan.locations().size();
        batch.placements = plan.slots().size();
        batch.quantities = quantities;
        batch.minimums = minimums;
        batch.errors = (int) plan.count(ImportPlanner.Status.ERROR);
        batch.skipped = (int)
                (plan.count(ImportPlanner.Status.DUPLICATE) + plan.count(ImportPlanner.Status.POSSIBLE_DUPLICATE));
        batches.saveAndFlush(batch);
        var metadata = new LinkedHashMap<String, String>();
        if (fileName != null) metadata.put("file", fileName);
        metadata.put("rows", String.valueOf(batch.rows));
        metadata.put("itemsCreated", String.valueOf(batch.itemsCreated));
        metadata.put("locationsCreated", String.valueOf(batch.locationsCreated));
        metadata.put("skipped", String.valueOf(batch.skipped));
        metadata.put("errors", String.valueOf(batch.errors));
        audit.record(
                orgId, actor.userId(), actor.displayName(), "ITEM_IMPORT_COMPLETED", "ORGANIZATION", orgId, metadata);
        return result(batch, false);
    }

    private ImportPlanner.Plan plan(UUID orgId, String csv, Set<Integer> include) {
        if (csv.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
        var parsed = ImportPlanner.parse(csv);
        var skus = parsed.stream()
                .map(ImportPlanner.Parsed::sku)
                .filter(Objects::nonNull)
                .filter(sku -> sku.length() <= 80)
                .distinct()
                .toList();
        var names = parsed.stream()
                .filter(row -> row.sku() == null && !row.name().isEmpty())
                .map(row -> ImportPlanner.normalize(row.name()))
                .distinct()
                .toList();
        var tree = locationQueries.tree(orgId);
        var counts = contents.itemCounts(
                orgId, tree.stream().map(LocationQueries.Node::id).toList());
        var snapshot = new ImportPlanner.Snapshot(
                items.existingSkus(orgId, skus), items.activeNames(orgId, names), tree, counts);
        return ImportPlanner.plan(parsed, snapshot, include);
    }

    private static Summary summary(ImportPlanner.Plan plan) {
        return new Summary(
                plan.rows().size(),
                plan.items().size(),
                (int) plan.count(ImportPlanner.Status.DUPLICATE),
                (int) plan.count(ImportPlanner.Status.POSSIBLE_DUPLICATE),
                (int) plan.count(ImportPlanner.Status.ERROR),
                plan.slots().size(),
                (int) plan.slots().stream()
                        .filter(slot -> slot.quantity() != null)
                        .count(),
                (int) plan.slots().stream()
                        .filter(slot -> slot.minimum() != null)
                        .count(),
                plan.locations().stream()
                        .map(ImportPlanner.PlannedLocation::path)
                        .toList());
    }

    private static Result result(ImportBatch batch, boolean replayed) {
        return new Result(
                replayed,
                batch.rows,
                batch.itemsCreated,
                batch.locationsCreated,
                batch.placements,
                batch.quantities,
                batch.minimums,
                batch.skipped,
                batch.errors);
    }

    static PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException(
                "IMPORT_TOO_LARGE", "That file is larger than 1 MB. Split it into smaller files.");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
