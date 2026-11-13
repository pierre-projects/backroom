package com.backroom.locations;

import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.NotFoundException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read API that other modules use instead of the location repository. */
@Service
@Transactional(readOnly = true)
public class LocationQueries {
    /** A location's own name and its parent's name, so two "Shelf A" locations can be told apart. */
    public record Label(String name, String parentName) {
        public String path() {
            return parentName == null ? name : parentName + " › " + name;
        }
    }
    /** What a label or scan result shows for a location. */
    public record Summary(UUID id, String name, String parentName, String type, boolean archived) {
        public String path() {
            return parentName == null ? name : parentName + " › " + name;
        }
    }

    private final LocationRepository locations;

    LocationQueries(LocationRepository locations) {
        this.locations = locations;
    }

    public Summary require(UUID orgId, UUID id) {
        var summary = summaries(orgId, List.of(id)).get(id);
        if (summary == null) throw new NotFoundException();
        return summary;
    }

    public Map<UUID, Summary> summaries(UUID orgId, Collection<UUID> ids) {
        var result = new HashMap<UUID, Summary>();
        if (ids.isEmpty()) return result;
        var found = locations.findByOrganizationIdAndIdIn(orgId, ids);
        var parents = names(
                orgId,
                found.stream()
                        .map(location -> location.getParentId())
                        .filter(Objects::nonNull)
                        .toList());
        found.forEach(location -> result.put(
                location.getId(),
                new Summary(
                        location.getId(),
                        location.getName(),
                        location.getParentId() == null ? null : parents.get(location.getParentId()),
                        location.getType().name(),
                        location.isArchived())));
        return result;
    }
    /** Unarchived locations whose name contains the text, by name, with their parent's name. */
    public List<Summary> search(UUID orgId, String q, int limit) {
        var found = locations
                .search(orgId, null, false, false, false, q, com.backroom.shared.web.PageResponse.named(0, limit))
                .getContent();
        var parents = names(
                orgId,
                found.stream()
                        .map(location -> location.getParentId())
                        .filter(Objects::nonNull)
                        .toList());
        return found.stream()
                .map(location -> new Summary(
                        location.getId(),
                        location.getName(),
                        location.getParentId() == null ? null : parents.get(location.getParentId()),
                        location.getType().name(),
                        location.isArchived()))
                .toList();
    }
    /** One location in the whole tree, for planning imports. */
    public record Node(UUID id, String name, UUID parentId, String type, boolean archived, boolean hasActiveChildren) {}
    /** Every location in the business, archived ones included, with whether each has active children. */
    public List<Node> tree(UUID orgId) {
        var all = locations.findByOrganizationId(orgId);
        var parents = new HashSet<UUID>();
        all.stream()
                .filter(location -> !location.isArchived() && location.getParentId() != null)
                .forEach(location -> parents.add(location.getParentId()));
        return all.stream()
                .map(location -> new Node(
                        location.getId(),
                        location.getName(),
                        location.getParentId(),
                        location.getType().name(),
                        location.isArchived(),
                        parents.contains(location.getId())))
                .toList();
    }

    public void requireExists(UUID orgId, UUID id) {
        locations.findByOrganizationIdAndId(orgId, id).orElseThrow(NotFoundException::new);
    }

    public void requireActiveLeaf(UUID orgId, UUID id) {
        var location = locations.findByOrganizationIdAndId(orgId, id).orElseThrow(NotFoundException::new);
        if (location.isArchived() || locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw notLeaf();
        }
    }

    /**
     * Holds a shared row lock on an active leaf for the rest of the caller's transaction, so the location cannot be
     * archived or given children while stock is being added to it. Must run inside the caller's write transaction.
     */
    @Transactional
    public void lockActiveLeafForShare(UUID orgId, UUID id) {
        var location = locations.lockForShare(orgId, id).orElseThrow(NotFoundException::new);
        if (location.isArchived() || locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw notLeaf();
        }
    }

    public Map<UUID, String> names(UUID orgId, Collection<UUID> ids) {
        var names = new HashMap<UUID, String>();
        if (!ids.isEmpty())
            locations
                    .findByOrganizationIdAndIdIn(orgId, ids)
                    .forEach(location -> names.put(location.getId(), location.getName()));
        return names;
    }

    public Map<UUID, Label> labels(UUID orgId, Collection<UUID> ids) {
        var labels = new HashMap<UUID, Label>();
        if (ids.isEmpty()) return labels;
        var found = locations.findByOrganizationIdAndIdIn(orgId, ids);
        var parentIds = found.stream()
                .map(location -> location.getParentId())
                .filter(Objects::nonNull)
                .toList();
        var parents = names(orgId, parentIds);
        found.forEach(location -> labels.put(
                location.getId(),
                new Label(
                        location.getName(),
                        location.getParentId() == null ? null : parents.get(location.getParentId()))));
        return labels;
    }

    private static ConflictException notLeaf() {
        return new ConflictException("LOCATION_NOT_LEAF", "Choose an active location with no active child locations.");
    }
}
