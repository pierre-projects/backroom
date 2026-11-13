package com.backroom.locations;

import java.util.UUID;

/** Implemented by inventory without making locations depend on inventory repositories. */
public interface LocationMutationPolicy {
    void assertNoActivePlacements(UUID organizationId, UUID locationId);
}
