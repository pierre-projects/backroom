package com.backroom.inventory;

import com.backroom.access.*;
import com.backroom.audit.AuditService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.*;
import java.util.*;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.backroom.access.Role.*;

@Service
@Transactional
public class CatalogService {
    private final ItemRepository items;
    private final PlacementRepository placements;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final AuditService audit;
    CatalogService(ItemRepository items, PlacementRepository placements, LocationQueries locations, AccessPolicy access, AuditService audit) {
        this.items=items; this.placements=placements; this.locations=locations; this.access=access; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public PageResponse<CatalogDtos.View> list(UUID orgId, boolean includeArchived, String q, int page, int size) {
        access.require(orgId);
        return PageResponse.from(items.search(orgId, includeArchived, q.strip(), PageResponse.named(page, size)).map(this::view));
    }
    @Transactional(readOnly=true)
    public CatalogDtos.View get(UUID orgId, UUID id) { access.require(orgId); return view(find(orgId, id)); }
    @Transactional(readOnly=true)
    public PageResponse<CatalogDtos.View> atLocation(UUID orgId, UUID locationId, int page, int size) {
        access.require(orgId); locations.requireExists(orgId, locationId);
        return PageResponse.from(items.atLocation(orgId, locationId, PageResponse.named(page, size)).map(this::view));
    }
    public CatalogDtos.View create(UUID orgId, CatalogDtos.Input input) {
        access.lock(orgId, OWNER, MANAGER);
        var sku=normalizeSku(input.sku());
        checkSku(orgId, sku, new UUID(0, 0));
        var item=items.saveAndFlush(new Item(orgId, input.name().strip(), sku, input.unit()));
        record(orgId, "ITEM_CREATED", "ITEM", item.getId(), metadata(item));
        return view(item);
    }
    public CatalogDtos.View edit(UUID orgId, UUID id, CatalogDtos.Edit input) {
        access.lock(orgId, OWNER, MANAGER);
        var item=find(orgId, id);
        editable(item); item.checkVersion(input.expectedVersion());
        var sku=normalizeSku(input.sku()); checkSku(orgId, sku, id);
        item.name=input.name().strip(); item.sku=sku; item.unit=input.unit();
        items.flush();
        record(orgId, "ITEM_UPDATED", "ITEM", id, metadata(item));
        return view(item);
    }
    public void archive(UUID orgId, UUID id, Long version) {
        access.lock(orgId, OWNER, MANAGER);
        var item=find(orgId, id);
        item.checkVersion(version);
        if (item.archived) return;
        item.archived=true;
        placements.findByOrganizationIdAndItemIdAndActiveTrue(orgId, id).forEach(placement -> placement.active=false);
        items.flush();
        record(orgId, "ITEM_ARCHIVED", "ITEM", id, metadata(item));
    }
    @Transactional(readOnly=true)
    public PageResponse<CatalogDtos.PlacementView> placements(UUID orgId, UUID itemId, int page, int size) {
        access.require(orgId); find(orgId, itemId);
        var result=placements.findByOrganizationIdAndItemIdAndActiveTrue(orgId, itemId,
                PageResponse.request(page, size, Sort.by("createdAt", "id")));
        var names=locations.names(orgId, result.stream().map(placement -> placement.locationId).toList());
        return PageResponse.from(result.map(placement -> placementView(placement, names.get(placement.locationId))));
    }
    public CatalogDtos.PlacementView place(UUID orgId, UUID itemId, UUID locationId) {
        access.lock(orgId, OWNER, MANAGER);
        editable(find(orgId, itemId));
        locations.requireActiveLeaf(orgId, locationId);
        var placement=placements.findByOrganizationIdAndItemIdAndLocationId(orgId, itemId, locationId).orElse(null);
        if (placement == null) placement=new ItemPlacement(orgId, itemId, locationId);
        else if (placement.active) return placementView(placement, locations.names(orgId, List.of(locationId)).get(locationId));
        placement.active=true;
        placements.saveAndFlush(placement);
        record(orgId, "PLACEMENT_ADDED", "ITEM", itemId, Map.of("locationId", locationId.toString()));
        return placementView(placement, locations.names(orgId, List.of(locationId)).get(locationId));
    }
    public void removePlacement(UUID orgId, UUID itemId, UUID locationId) {
        access.lock(orgId, OWNER, MANAGER); find(orgId, itemId);
        var placement=placements.findByOrganizationIdAndItemIdAndLocationId(orgId, itemId, locationId).orElseThrow(ApiException::notFound);
        if (!placement.active) return;
        placement.active=false; placements.flush();
        record(orgId, "PLACEMENT_REMOVED", "ITEM", itemId, Map.of("locationId", locationId.toString()));
    }
    private Item find(UUID orgId, UUID id) { return items.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound); }
    private void editable(Item item) { if (item.archived) throw ApiException.conflict("ARCHIVED", "Archived items cannot be changed."); }
    private String normalizeSku(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized=value.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 80) throw ApiException.badRequest("SKU must be at most 80 characters after normalization.");
        return normalized;
    }
    private void checkSku(UUID orgId, String sku, UUID id) {
        if (sku != null && items.existsByOrganizationIdAndSkuAndIdNot(orgId, sku, id)) {
            throw ApiException.conflict("SKU_IN_USE", "This SKU is already reserved, including archived items.");
        }
    }
    private CatalogDtos.View view(Item item) {
        return new CatalogDtos.View(item.getId(), item.name, item.sku, item.unit, item.archived, item.getVersion());
    }
    private CatalogDtos.PlacementView placementView(ItemPlacement placement, String name) {
        return new CatalogDtos.PlacementView(placement.getId(), placement.itemId, placement.locationId, name);
    }
    private Map<String, String> metadata(Item item) { return Map.of("name", item.name, "sku", Objects.toString(item.sku, ""), "unit", item.unit.name()); }
    private void record(UUID orgId, String action, String type, UUID id, Map<String, String> metadata) {
        var actor=access.actor(); audit.record(orgId, actor.userId(), actor.displayName(), action, type, id, metadata);
    }
}
