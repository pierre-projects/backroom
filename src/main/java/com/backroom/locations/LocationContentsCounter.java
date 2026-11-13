package com.backroom.locations;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Implemented by inventory: how many unarchived items are placed in each location. Keeps locations free of inventory. */
public interface LocationContentsCounter {
    Map<UUID, Long> itemCounts(UUID organizationId, Collection<UUID> locationIds);
}
