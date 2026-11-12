package com.backroom.locations;

import com.backroom.access.*;
import com.backroom.audit.AuditService;
import com.backroom.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.backroom.access.Role.*;

@Service
@Transactional
public class LocationService {
    private final LocationRepository locations;
    private final AccessPolicy access;
    private final LocationMutationPolicy storagePolicy;
    private final AuditService audit;
    LocationService(LocationRepository locations, AccessPolicy access, LocationMutationPolicy storagePolicy, AuditService audit) {
        this.locations=locations; this.access=access; this.storagePolicy=storagePolicy; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public PageResponse<LocationDtos.View> list(UUID orgId, UUID parentId, boolean rootsOnly, boolean leafOnly,
                                               boolean includeArchived, String q, int page, int size) {
        access.require(orgId);
        if (parentId != null) find(orgId, parentId);
        return PageResponse.from(locations.search(orgId, parentId, rootsOnly, leafOnly, includeArchived, q.strip(),
                PageResponse.named(page, size)).map(this::view));
    }
    @Transactional(readOnly=true)
    public LocationDtos.View get(UUID orgId, UUID id) { access.require(orgId); return view(find(orgId, id)); }

    public LocationDtos.View create(UUID orgId, LocationDtos.Input input) {
        access.lock(orgId, OWNER, MANAGER);
        validateParent(orgId, null, input.parentId());
        var location = locations.saveAndFlush(new StorageLocation(orgId, input.name().strip(), input.type(), input.parentId()));
        record(location, "LOCATION_CREATED");
        return view(location);
    }
    public LocationDtos.View edit(UUID orgId, UUID id, LocationDtos.Edit input) {
        access.lock(orgId, OWNER, MANAGER);
        var location=find(orgId, id);
        editable(location);
        location.checkVersion(input.expectedVersion());
        validateParent(orgId, id, input.parentId());
        location.name=input.name().strip(); location.type=input.type(); location.parentId=input.parentId();
        locations.flush();
        record(location, "LOCATION_UPDATED");
        return view(location);
    }
    public void archive(UUID orgId, UUID id, Long version) {
        access.lock(orgId, OWNER, MANAGER);
        var location=find(orgId, id);
        location.checkVersion(version);
        if (location.archived) return;
        if (locations.existsByOrganizationIdAndParentIdAndArchivedFalse(orgId, id)) {
            throw ApiException.conflict("LOCATION_HAS_CHILDREN", "Archive or move active child locations first.");
        }
        storagePolicy.assertNoActivePlacements(orgId, id);
        location.archived=true;
        locations.flush();
        record(location, "LOCATION_ARCHIVED");
    }
    private void validateParent(UUID orgId, UUID self, UUID parentId) {
        var cursor=parentId;
        var visited=new HashSet<UUID>();
        while (cursor != null) {
            if (cursor.equals(self) || !visited.add(cursor)) {
                throw ApiException.conflict("LOCATION_CYCLE", "A location cannot contain itself or its ancestors.");
            }
            var parent=find(orgId, cursor);
            editable(parent);
            cursor=parent.parentId;
        }
        if (parentId != null) storagePolicy.assertNoActivePlacements(orgId, parentId);
    }
    private void editable(StorageLocation location) {
        if (location.archived) throw ApiException.conflict("ARCHIVED", "Archived locations cannot be changed or used as parents.");
    }
    private StorageLocation find(UUID orgId, UUID id) {
        return locations.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound);
    }
    private LocationDtos.View view(StorageLocation location) {
        return new LocationDtos.View(location.getId(), location.name, location.type, location.parentId, location.archived, location.getVersion());
    }
    private void record(StorageLocation location, String action) {
        var actor=access.actor();
        audit.record(location.getOrganizationId(), actor.userId(), actor.displayName(), action, "LOCATION", location.getId(),
                Map.of("name", location.name, "type", location.type.name(), "parentId", Objects.toString(location.parentId, "")));
    }
}
