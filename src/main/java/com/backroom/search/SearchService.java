package com.backroom.search;

import com.backroom.access.AccessPolicy;
import com.backroom.inventory.ItemQueries;
import com.backroom.inventory.Unit;
import com.backroom.labels.LabelDtos;
import com.backroom.labels.ScanService;
import com.backroom.locations.LocationQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One search box for the whole business: items by name or SKU, locations by name, and an exact QR code or barcode.
 * Reads other modules only through their query services.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {
    static final int LIMIT = 8;

    public record ItemHit(UUID id, String name, String sku, Unit unit) {}

    public record LocationHit(UUID id, String name, String parentName, String type) {}
    /** A code that resolved to something in this business; null when the text isn't one. */
    public record CodeHit(LabelDtos.TargetType type, UUID id, String name, String parentName) {}

    public record Results(String q, List<ItemHit> items, List<LocationHit> locations, CodeHit code) {}

    private final AccessPolicy access;
    private final ItemQueries items;
    private final LocationQueries locations;
    private final ScanService scans;

    SearchService(AccessPolicy access, ItemQueries items, LocationQueries locations, ScanService scans) {
        this.access = access;
        this.items = items;
        this.locations = locations;
        this.scans = scans;
    }

    public Results search(UUID orgId, String raw) {
        access.require(orgId);
        var q = raw == null ? "" : raw.strip();
        if (q.length() > 100) q = q.substring(0, 100);
        if (q.isEmpty()) return new Results(q, List.of(), List.of(), null);
        var itemHits = items.search(orgId, q, LIMIT).stream()
                .map(item -> new ItemHit(item.id(), item.name(), item.sku(), item.unit()))
                .toList();
        var locationHits = locations.search(orgId, q, LIMIT).stream()
                .map(location ->
                        new LocationHit(location.id(), location.name(), location.parentName(), location.type()))
                .toList();
        return new Results(q, itemHits, locationHits, code(orgId, q));
    }

    private CodeHit code(UUID orgId, String q) {
        if (q.length() < 6 || q.length() > 500) return null;
        var result = scans.resolve(orgId, new LabelDtos.ScanInput(q, null));
        if (result.status() != LabelDtos.ScanStatus.ITEM && result.status() != LabelDtos.ScanStatus.LOCATION)
            return null;
        if (result.archived()) return null;
        return new CodeHit(result.targetType(), result.id(), result.name(), result.parentName());
    }
}
