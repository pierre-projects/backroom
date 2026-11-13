package com.backroom.inventory;
import com.backroom.locations.LocationContentsCounter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class PlacementContentsCounter implements LocationContentsCounter {
    private final PlacementRepository placements;
    PlacementContentsCounter(PlacementRepository placements) { this.placements=placements; }
    @Override public Map<UUID, Long> itemCounts(UUID orgId, Collection<UUID> locationIds) {
        var counts = new HashMap<UUID, Long>();
        if (!locationIds.isEmpty()) placements.itemCounts(orgId, locationIds).forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
        return counts;
    }
}
