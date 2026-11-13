package com.backroom.inventory;

import com.backroom.locations.LocationMutationPolicy;
import com.backroom.shared.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PlacementLocationPolicy implements LocationMutationPolicy {
    private final PlacementRepository placements;
    private final StockBalanceRepository balances;

    PlacementLocationPolicy(PlacementRepository placements, StockBalanceRepository balances) {
        this.placements = placements;
        this.balances = balances;
    }

    @Override
    public void assertNoActivePlacements(UUID orgId, UUID locationId) {
        if (balances.existsByOrganizationIdAndLocationIdAndQuantityGreaterThan(
                orgId, locationId, java.math.BigDecimal.ZERO)) {
            throw ApiException.conflict(
                    "LOCATION_HAS_STOCK", "This location still holds stock. Move or use it up first.");
        }
        if (placements.existsByOrganizationIdAndLocationIdAndActiveTrue(orgId, locationId)) {
            throw ApiException.conflict(
                    "LOCATION_HAS_PLACEMENTS", "Remove or move this location's expected item placements first.");
        }
    }
}
