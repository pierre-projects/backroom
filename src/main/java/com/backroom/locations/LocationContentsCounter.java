package com.backroom.locations;
import java.util.*;

/** Implemented by inventory: how many unarchived items are placed in each location. Keeps locations free of inventory. */
public interface LocationContentsCounter {
    Map<UUID, Long> itemCounts(UUID organizationId, Collection<UUID> locationIds);
}
