package com.backroom.imports;

import com.backroom.inventory.Quantities;
import com.backroom.inventory.Unit;
import com.backroom.locations.LocationQueries;
import com.backroom.locations.LocationType;
import com.backroom.shared.error.DomainException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.PayloadTooLargeException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a CSV file into an import plan without touching the database: which rows are new, duplicates or errors, which
 * locations must be created, and which items, placements, starting quantities and minimums result. The same plan is
 * computed for the preview and again, against current data, when the import is applied.
 */
final class ImportPlanner {
    static final int MAX_ROWS = 2_000;
    static final int MAX_DEPTH = 4;

    enum Status {
        NEW,
        DUPLICATE,
        POSSIBLE_DUPLICATE,
        ERROR
    }

    /** One data row as read, before anything is looked up. */
    record Parsed(
            int line,
            String name,
            String sku,
            Unit unit,
            String unitText,
            List<String> path,
            String locationText,
            BigDecimal quantity,
            String quantityText,
            BigDecimal minimum,
            String minimumText,
            List<String> errors) {
        String itemKey() {
            return sku != null ? "sku:" + sku : "name:" + normalize(name);
        }
    }
    /** What already exists in the business, read before planning. */
    record Snapshot(
            Set<String> skus, Set<String> activeNames, List<LocationQueries.Node> tree, Map<UUID, Long> itemCounts) {}

    record Row(
            int line,
            String item,
            String sku,
            String unit,
            String location,
            String quantity,
            String minimum,
            Status status,
            List<String> messages) {}

    record PlannedLocation(
            String key, String name, String path, String parentKey, UUID parentId, LocationType type, int depth) {}

    record PlannedItem(String key, String name, String sku, Unit unit) {}

    record PlannedSlot(
            int line,
            String itemKey,
            UUID locationId,
            String locationKey,
            String path,
            BigDecimal quantity,
            BigDecimal minimum) {}

    record Plan(List<Row> rows, List<PlannedLocation> locations, List<PlannedItem> items, List<PlannedSlot> slots) {
        long count(Status status) {
            return rows.stream().filter(row -> row.status() == status).count();
        }
    }

    private ImportPlanner() {}

    // ---------------------------------------------------------------- reading

    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("item", "item"),
            Map.entry("name", "item"),
            Map.entry("item name", "item"),
            Map.entry("sku", "sku"),
            Map.entry("unit", "unit"),
            Map.entry("base unit", "unit"),
            Map.entry("location", "location"),
            Map.entry("place", "location"),
            Map.entry("starting quantity", "quantity"),
            Map.entry("on hand", "quantity"),
            Map.entry("quantity", "quantity"),
            Map.entry("opening quantity", "quantity"),
            Map.entry("minimum", "minimum"),
            Map.entry("minimum quantity", "minimum"),
            Map.entry("min", "minimum"));
    private static final Map<String, Unit> UNITS = Map.ofEntries(
            Map.entry("each", Unit.EACH),
            Map.entry("ea", Unit.EACH),
            Map.entry("pcs", Unit.EACH),
            Map.entry("pieces", Unit.EACH),
            Map.entry("g", Unit.GRAM),
            Map.entry("gram", Unit.GRAM),
            Map.entry("grams", Unit.GRAM),
            Map.entry("kg", Unit.KILOGRAM),
            Map.entry("kilogram", Unit.KILOGRAM),
            Map.entry("kilograms", Unit.KILOGRAM),
            Map.entry("ml", Unit.MILLILITER),
            Map.entry("milliliter", Unit.MILLILITER),
            Map.entry("milliliters", Unit.MILLILITER),
            Map.entry("millilitre", Unit.MILLILITER),
            Map.entry("millilitres", Unit.MILLILITER),
            Map.entry("l", Unit.LITER),
            Map.entry("liter", Unit.LITER),
            Map.entry("liters", Unit.LITER),
            Map.entry("litre", Unit.LITER),
            Map.entry("litres", Unit.LITER));
    static final Map<Unit, String> UNIT_LABELS =
            Map.of(Unit.EACH, "each", Unit.GRAM, "g", Unit.KILOGRAM, "kg", Unit.MILLILITER, "mL", Unit.LITER, "L");

    static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static List<Parsed> parse(String csv) {
        var records = CsvReader.read(csv);
        if (records.isEmpty()) throw new InvalidRequestException("The file is empty. Start from the template.");
        var columns = new HashMap<String, Integer>();
        var header = records.getFirst().cells();
        for (int index = 0; index < header.size(); index++) {
            var field = HEADERS.get(normalize(header.get(index)));
            if (field != null) columns.putIfAbsent(field, index);
        }
        if (!columns.containsKey("item") || !columns.containsKey("unit")) {
            throw new InvalidRequestException(
                    "IMPORT_HEADER",
                    "The first row must name the columns, including Item and Unit. Start from the template.");
        }
        var data = records.subList(1, records.size()).stream()
                .filter(record -> !record.blank())
                .toList();
        if (data.isEmpty()) throw new InvalidRequestException("The file has a header but no rows to import.");
        if (data.size() > MAX_ROWS) {
            throw new PayloadTooLargeException(
                    "IMPORT_TOO_LARGE",
                    "That file has " + data.size() + " rows; the limit is " + MAX_ROWS
                            + ". Split it into smaller files.");
        }
        return data.stream().map(record -> parseRow(record, columns)).toList();
    }

    private static Parsed parseRow(CsvReader.Record record, Map<String, Integer> columns) {
        var errors = new ArrayList<String>();
        var get = (java.util.function.Function<String, String>) field ->
                columns.containsKey(field) ? record.cell(columns.get(field)).strip() : "";
        var name = get.apply("item");
        if (name.isEmpty()) errors.add("Item name is missing.");
        else if (name.length() > 160) errors.add("Item name is longer than 160 characters.");
        var skuText = get.apply("sku");
        var sku = skuText.isEmpty() ? null : skuText.toUpperCase(Locale.ROOT);
        if (sku != null && sku.length() > 80) errors.add("SKU is longer than 80 characters.");
        var unitText = get.apply("unit");
        var unit = UNITS.get(normalize(unitText));
        if (unitText.isEmpty()) errors.add("Unit is missing. Use each, g, kg, mL or L.");
        else if (unit == null) errors.add("Unit \"" + unitText + "\" isn't one of each, g, kg, mL or L.");

        var locationText = get.apply("location");
        var path = new ArrayList<String>();
        if (!locationText.isEmpty()) {
            for (var step : locationText.split("[>›]", -1)) {
                var cleaned = step.strip().replaceAll("\\s+", " ");
                if (cleaned.isEmpty()) {
                    errors.add("Location \"" + locationText + "\" has an empty step.");
                    path.clear();
                    break;
                }
                if (cleaned.length() > 120) {
                    errors.add("Location names can be at most 120 characters.");
                    path.clear();
                    break;
                }
                path.add(cleaned);
            }
            if (path.size() > MAX_DEPTH) {
                errors.add("Location paths can be at most " + MAX_DEPTH + " levels deep.");
                path.clear();
            }
        }
        var quantityText = get.apply("quantity");
        var quantity = number(quantityText, unit, false, "Starting quantity", errors);
        var minimumText = get.apply("minimum");
        var minimum = number(minimumText, unit, true, "Minimum", errors);
        if (minimum != null && locationText.isEmpty()) errors.add("A minimum needs a Location.");
        if (quantity != null && quantity.signum() > 0 && locationText.isEmpty())
            errors.add("A starting quantity needs a Location.");
        return new Parsed(
                record.line(),
                name,
                sku,
                unit,
                unitText,
                List.copyOf(path),
                locationText,
                quantity,
                quantityText,
                minimum,
                minimumText,
                List.copyOf(errors));
    }

    /** "2,5" is read as 2.5 (European spreadsheets) unless the text also has a dot. */
    private static BigDecimal number(String text, Unit unit, boolean positive, String label, List<String> errors) {
        if (text.isEmpty()) return null;
        var cleaned = text.contains(".") ? text.replace(",", "") : text.replace(',', '.');
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException error) {
            errors.add(label + " \"" + text + "\" isn't a number.");
            return null;
        }
        if (unit == null) return null;
        try {
            return positive ? Quantities.positive(unit, value) : Quantities.nonNegative(unit, value);
        } catch (DomainException error) {
            errors.add(label + ": " + error.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- planning

    static Plan plan(List<Parsed> parsed, Snapshot snapshot, Set<Integer> include) {
        var messages = new LinkedHashMap<Integer, List<String>>();
        var status = new LinkedHashMap<Integer, Status>();
        for (var row : parsed) {
            messages.put(row.line(), new ArrayList<>(row.errors()));
            status.put(row.line(), row.errors().isEmpty() ? Status.NEW : Status.ERROR);
        }
        // Items: rows sharing an SKU (or, without one, a name) are one item and must agree on name and unit.
        var groups = new LinkedHashMap<String, List<Parsed>>();
        parsed.stream()
                .filter(row -> row.name() != null
                        && !row.name().isEmpty()
                        && row.unit() != null
                        && (row.sku() == null || row.sku().length() <= 80))
                .forEach(row -> groups.computeIfAbsent(row.itemKey(), key -> new ArrayList<>())
                        .add(row));
        for (var group : groups.values()) {
            var first = group.getFirst();
            var unitClash =
                    group.stream().filter(row -> row.unit() != first.unit()).findFirst();
            var nameClash = group.stream()
                    .filter(row -> !normalize(row.name()).equals(normalize(first.name())))
                    .findFirst();
            if (unitClash.isPresent() || (first.sku() != null && nameClash.isPresent())) {
                var reason = unitClash.isPresent()
                        ? "Rows for this item use different units (line " + first.line() + " uses "
                                + UNIT_LABELS.get(first.unit()) + ", line "
                                + unitClash.get().line() + " uses "
                                + UNIT_LABELS.get(unitClash.get().unit()) + ")."
                        : "Rows with SKU " + first.sku() + " use different item names (lines " + first.line() + " and "
                                + nameClash.get().line() + ").";
                group.forEach(row -> {
                    status.put(row.line(), Status.ERROR);
                    messages.get(row.line()).add(reason);
                });
                continue;
            }
            if (first.sku() != null && snapshot.skus().contains(first.sku())) {
                group.forEach(row -> {
                    if (status.get(row.line()) == Status.ERROR) return;
                    status.put(row.line(), Status.DUPLICATE);
                    messages.get(row.line())
                            .add("SKU " + first.sku() + " already exists. The existing item is left as it is.");
                });
            } else if (first.sku() == null
                    && snapshot.activeNames().contains(normalize(first.name()))
                    && group.stream().noneMatch(row -> include.contains(row.line()))) {
                group.forEach(row -> {
                    if (status.get(row.line()) == Status.ERROR) return;
                    status.put(row.line(), Status.POSSIBLE_DUPLICATE);
                    messages.get(row.line())
                            .add("An item called \"" + first.name()
                                    + "\" already exists. Include this row only if it is a different item.");
                });
            }
        }

        // Places: resolve each importable row's path against the tree, planning any locations that must be created.
        var tree = new LocationTree(snapshot);
        var resolved = new HashMap<Integer, Target>();
        for (var row : parsed) {
            if (status.get(row.line()) != Status.NEW || row.path().isEmpty()) continue;
            var target = tree.resolve(row.path());
            if (target.error() != null) {
                status.put(row.line(), Status.ERROR);
                messages.get(row.line()).add(target.error());
            } else resolved.put(row.line(), target);
        }
        // A place that would gain child locations from another row can't also hold items.
        var gainingChildren = new HashSet<String>();
        resolved.values().forEach(target -> gainingChildren.addAll(target.ancestorKeys()));
        var seenSlots = new HashMap<String, Integer>();
        for (var row : parsed) {
            var target = resolved.get(row.line());
            if (target == null) continue;
            String problem = null;
            if (gainingChildren.contains(target.key()))
                problem = "\"" + target.display()
                        + "\" would get locations inside it from another row, so items can't go on it directly.";
            var slot = row.itemKey() + "@" + target.key();
            if (problem == null && seenSlots.containsKey(slot))
                problem = "Line " + seenSlots.get(slot) + " already places this item on " + target.display() + ".";
            if (problem != null) {
                status.put(row.line(), Status.ERROR);
                messages.get(row.line()).add(problem);
                resolved.remove(row.line());
            } else seenSlots.put(slot, row.line());
        }

        // Build the plan from the rows that are still importable.
        var importable = parsed.stream()
                .filter(row -> status.get(row.line()) == Status.NEW)
                .toList();
        var items = new LinkedHashMap<String, PlannedItem>();
        importable.forEach(row ->
                items.putIfAbsent(row.itemKey(), new PlannedItem(row.itemKey(), row.name(), row.sku(), row.unit())));
        var locations = new LinkedHashMap<String, PlannedLocation>();
        var slots = new ArrayList<PlannedSlot>();
        for (var row : importable) {
            var target = resolved.get(row.line());
            if (target == null) continue;
            target.creates().forEach(location -> locations.putIfAbsent(location.key(), location));
            slots.add(new PlannedSlot(
                    row.line(),
                    row.itemKey(),
                    target.existingId(),
                    target.existingId() == null ? target.key() : null,
                    target.display(),
                    row.quantity() != null && row.quantity().signum() > 0 ? row.quantity() : null,
                    row.minimum()));
        }
        var orderedLocations = locations.values().stream()
                .sorted(Comparator.comparingInt(PlannedLocation::depth).thenComparing(PlannedLocation::path))
                .toList();
        var rows = parsed.stream()
                .map(row -> new Row(
                        row.line(),
                        row.name(),
                        row.sku(),
                        row.unit() == null ? row.unitText() : UNIT_LABELS.get(row.unit()),
                        resolved.containsKey(row.line())
                                ? resolved.get(row.line()).display()
                                : row.path().isEmpty() ? row.locationText() : String.join(" › ", row.path()),
                        row.quantityText(),
                        row.minimumText(),
                        status.get(row.line()),
                        List.copyOf(messages.get(row.line()))))
                .toList();
        return new Plan(rows, orderedLocations, List.copyOf(items.values()), slots);
    }

    /** Where a path lands: an existing location, or a key for one this import creates (with the locations it creates). */
    record Target(
            String key,
            UUID existingId,
            String display,
            List<PlannedLocation> creates,
            List<String> ancestorKeys,
            String error) {
        static Target failed(String error) {
            return new Target(null, null, null, List.of(), List.of(), error);
        }
    }

    /** The business's locations indexed by parent, for walking paths case- and whitespace-insensitively. */
    private static final class LocationTree {
        private final Map<UUID, List<LocationQueries.Node>> children = new HashMap<>();
        private final Map<UUID, LocationQueries.Node> byId = new HashMap<>();
        private final Map<UUID, Long> itemCounts;
        private static final UUID ROOT = new UUID(0, 0);

        LocationTree(Snapshot snapshot) {
            itemCounts = snapshot.itemCounts();
            for (var node : snapshot.tree()) {
                byId.put(node.id(), node);
                children.computeIfAbsent(node.parentId() == null ? ROOT : node.parentId(), key -> new ArrayList<>())
                        .add(node);
            }
        }

        Target resolve(List<String> path) {
            UUID current = null; // existing location reached so far (null = top level)
            String plannedKey = null; // set once the path leaves existing locations
            var creates = new ArrayList<PlannedLocation>();
            var ancestors = new ArrayList<String>();
            var display = new ArrayList<String>();
            for (int depth = 0; depth < path.size(); depth++) {
                var step = path.get(depth);
                if (depth > 0) ancestors.add(plannedKey != null ? plannedKey : "id:" + current);
                if (plannedKey == null) {
                    var matches = children.getOrDefault(current == null ? ROOT : current, List.of()).stream()
                            .filter(node -> normalize(node.name()).equals(normalize(step)))
                            .toList();
                    var active =
                            matches.stream().filter(node -> !node.archived()).toList();
                    if (active.size() > 1)
                        return Target.failed("More than one location is called \"" + step + "\""
                                + (current == null
                                        ? " at the top level"
                                        : " in " + byId.get(current).name()) + ". Rename one so the path is clear.");
                    if (active.size() == 1) {
                        current = active.getFirst().id();
                        display.add(active.getFirst().name());
                        continue;
                    }
                    if (!matches.isEmpty())
                        return Target.failed("Location \"" + matches.getFirst().name()
                                + "\" is archived. Restore it or use another location.");
                    if (current != null && itemCounts.getOrDefault(current, 0L) > 0) {
                        return Target.failed("\"" + byId.get(current).name()
                                + "\" holds items, so new locations can't be created inside it.");
                    }
                }
                var parentKey = plannedKey;
                var parentId = plannedKey == null ? current : null;
                display.add(step);
                plannedKey =
                        "new:" + display.stream().map(ImportPlanner::normalize).collect(Collectors.joining(">"));
                creates.add(new PlannedLocation(
                        plannedKey,
                        step,
                        String.join(" › ", display),
                        parentKey,
                        parentId,
                        depth == 0 ? LocationType.ROOM : LocationType.SHELF,
                        depth));
            }
            if (plannedKey == null) {
                var node = byId.get(current);
                if (node.hasActiveChildren())
                    return Target.failed(
                            "\"" + node.name() + "\" has locations inside it. Put items on one of those instead.");
                return new Target("id:" + current, current, String.join(" › ", display), List.of(), ancestors, null);
            }
            return new Target(plannedKey, null, String.join(" › ", display), creates, ancestors, null);
        }
    }
}
