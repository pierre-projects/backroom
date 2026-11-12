package com.backroom.inventory;

import com.backroom.shared.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read API that other modules (labels, later restocking) use instead of the item repository. No access checks: callers authorize. */
@Service
@Transactional(readOnly=true)
public class ItemQueries {
    public record ItemSummary(UUID id, String name, String sku, Unit unit, boolean archived) {}
    private final ItemRepository items;
    ItemQueries(ItemRepository items) { this.items=items; }

    public ItemSummary require(UUID orgId, UUID id) {
        return summary(items.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound));
    }
    public Map<UUID, ItemSummary> summaries(UUID orgId, Collection<UUID> ids) {
        var result = new HashMap<UUID, ItemSummary>();
        if (!ids.isEmpty()) items.findAllById(ids).stream().filter(item -> item.getOrganizationId().equals(orgId))
                .forEach(item -> result.put(item.getId(), summary(item)));
        return result;
    }
    private static ItemSummary summary(Item item) { return new ItemSummary(item.getId(), item.name, item.sku, item.unit, item.archived); }
}
