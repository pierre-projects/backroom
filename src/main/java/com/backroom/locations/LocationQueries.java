package com.backroom.locations;

import com.backroom.shared.ApiException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read API that other modules use instead of the location repository. */
@Service
@Transactional(readOnly=true)
public class LocationQueries {
    /** A location's own name and its parent's name, so two "Shelf A" locations can be told apart. */
    public record Label(String name, String parentName) {
        public String path() { return parentName == null ? name : parentName + " › " + name; }
    }
    private final LocationRepository locations;
    LocationQueries(LocationRepository locations) { this.locations=locations; }
    public void requireExists(UUID orgId, UUID id) {
        locations.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound);
    }

    public void requireActiveLeaf(UUID orgId, UUID id) {
        var location = locations.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound);
        if (location.archived || locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw notLeaf();
        }
    }

    /**
     * Holds a shared row lock on an active leaf for the rest of the caller's transaction, so the location cannot be
     * archived or given children while stock is being added to it. Must run inside the caller's write transaction.
     */
    @Transactional
    public void lockActiveLeafForShare(UUID orgId, UUID id) {
        var location = locations.lockForShare(orgId, id).orElseThrow(ApiException::notFound);
        if (location.archived || locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw notLeaf();
        }
    }

    public Map<UUID, String> names(UUID orgId, Collection<UUID> ids) {
        var names = new HashMap<UUID, String>();
        if (!ids.isEmpty()) locations.findByOrganizationIdAndIdIn(orgId, ids).forEach(location -> names.put(location.getId(), location.name));
        return names;
    }

    public Map<UUID, Label> labels(UUID orgId, Collection<UUID> ids) {
        var labels = new HashMap<UUID, Label>();
        if (ids.isEmpty()) return labels;
        var found = locations.findByOrganizationIdAndIdIn(orgId, ids);
        var parentIds = found.stream().map(location -> location.parentId).filter(Objects::nonNull).toList();
        var parents = names(orgId, parentIds);
        found.forEach(location -> labels.put(location.getId(),
                new Label(location.name, location.parentId == null ? null : parents.get(location.parentId))));
        return labels;
    }

    private static ApiException notLeaf() {
        return ApiException.conflict("LOCATION_NOT_LEAF", "Choose an active location with no active child locations.");
    }
}
