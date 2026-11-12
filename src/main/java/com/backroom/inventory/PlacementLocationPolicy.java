package com.backroom.inventory;
import com.backroom.locations.LocationMutationPolicy;
import com.backroom.shared.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PlacementLocationPolicy implements LocationMutationPolicy {
    private final PlacementRepository placements;
    PlacementLocationPolicy(PlacementRepository placements) { this.placements=placements; }
    @Override public void assertNoActivePlacements(UUID orgId, UUID locationId) {
        if (placements.existsByOrganizationIdAndLocationIdAndActiveTrue(orgId, locationId)) {
            throw ApiException.conflict("LOCATION_HAS_PLACEMENTS", "Remove or move this location's expected item placements first.");
        }
    }
}
