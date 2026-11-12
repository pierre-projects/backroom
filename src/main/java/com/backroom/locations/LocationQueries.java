package com.backroom.locations;

import com.backroom.shared.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class LocationQueries {
    private final LocationRepository locations;
    LocationQueries(LocationRepository locations) { this.locations=locations; }
    public void requireExists(UUID orgId, UUID id) {
        locations.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound);
    }

    public void requireActiveLeaf(UUID orgId, UUID id) {
        var location = locations.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound);
        if (location.archived || locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw ApiException.conflict("LOCATION_NOT_LEAF", "Choose an active location with no active child locations.");
        }
    }
    public Map<UUID, String> names(UUID orgId, Collection<UUID> ids) {
        var names = new HashMap<UUID, String>();
        if (!ids.isEmpty()) locations.findByOrganizationIdAndIdIn(orgId, ids).forEach(location -> names.put(location.getId(), location.name));
        return names;
    }
}
