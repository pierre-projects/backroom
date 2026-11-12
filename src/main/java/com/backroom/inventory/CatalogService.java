package com.backroom.inventory;

import com.backroom.access.*;
import com.backroom.audit.AuditService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.*;
import java.math.BigDecimal;
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
    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final AuditService audit;
    CatalogService(ItemRepository items, PlacementRepository placements, StockBalanceRepository balances, StockMovementRepository movements,
                   LocationQueries locations, AccessPolicy access, AuditService audit) {
        this.items=items; this.placements=placements; this.balances=balances; this.movements=movements;
        this.locations=locations; this.access=access; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public PageResponse<CatalogDtos.View> list(UUID orgId, boolean includeArchived, String q, UUID locationId, boolean lowStock, int page, int size) {
        access.require(orgId);
        if (locationId != null) locations.requireExists(orgId, locationId);
        var result=items.search(orgId, includeArchived, q.strip(), locationId, lowStock, PageResponse.named(page, size));
        var ids=result.stream().map(Item::getId).toList();
        var totals=totals(orgId, ids);
        var low=ids.isEmpty() ? Set.<UUID>of() : balances.lowAmong(orgId, ids);
        return PageResponse.from(result.map(item -> view(item, totals.get(item.getId()), low.contains(item.getId()))));
    }
    @Transactional(readOnly=true)
    public CatalogDtos.View get(UUID orgId, UUID id) { access.require(orgId); return view(find(orgId, id)); }
    @Transactional(readOnly=true)
    public PageResponse<CatalogDtos.View> atLocation(UUID orgId, UUID locationId, int page, int size) {
        access.require(orgId); locations.requireExists(orgId, locationId);
        var result=items.atLocation(orgId, locationId, PageResponse.named(page, size));
        var here=new HashMap<UUID, BigDecimal>();
        if (!result.isEmpty()) balances.findByOrganizationIdAndLocationIdAndItemIdIn(orgId, locationId, result.stream().map(Item::getId).toList())
                .forEach(balance -> here.put(balance.itemId, balance.quantity));
        return PageResponse.from(result.map(item -> view(item, here.getOrDefault(item.getId(), BigDecimal.ZERO))));
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
        if (input.unit() != item.unit && movements.existsByOrganizationIdAndItemId(orgId, id)) {
            throw ApiException.conflict("UNIT_LOCKED", "The unit can't change after stock has been recorded. Create a new item instead.");
        }
        item.name=input.name().strip(); item.sku=sku; item.unit=input.unit();
        items.flush();
        record(orgId, "ITEM_UPDATED", "ITEM", id, metadata(item));
        return view(item);
    }
    public void archive(UUID orgId, UUID id, Long version) {
        access.lock(orgId, OWNER, MANAGER);
        // Exclusive row lock: waits for in-flight stock commands, which share-lock the item.
        var item=items.lockForUpdate(orgId, id).orElseThrow(ApiException::notFound);
        item.checkVersion(version);
        if (item.archived) return;
        if (balances.existsByOrganizationIdAndItemIdAndQuantityGreaterThan(orgId, id, BigDecimal.ZERO)) {
            throw ApiException.conflict("ITEM_HAS_STOCK", "Use, move or count this item's remaining stock to zero before archiving it.");
        }
        item.archived=true;
        placements.findByOrganizationIdAndItemIdAndActiveTrue(orgId, id).forEach(placement -> { placement.active=false; placement.minimumQuantity=null; });
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
        balances.insertIfMissing(orgId, itemId, locationId);
        record(orgId, "PLACEMENT_ADDED", "ITEM", itemId, Map.of("locationId", locationId.toString()));
        return placementView(placement, locations.names(orgId, List.of(locationId)).get(locationId));
    }
    public void removePlacement(UUID orgId, UUID itemId, UUID locationId) {
        access.lock(orgId, OWNER, MANAGER); find(orgId, itemId);
        // Lock order matches stock commands: placement, then balance.
        var placement=placements.lockSlot(orgId, itemId, locationId).orElseThrow(ApiException::notFound);
        if (!placement.active) return;
        var balance=balances.lockSlot(orgId, itemId, locationId);
        if (balance.isPresent() && balance.get().quantity.signum() > 0) {
            throw ApiException.conflict("PLACEMENT_HAS_STOCK", "This location still holds stock of the item. Use, move or count it to zero first.");
        }
        placement.active=false; placement.minimumQuantity=null; placements.flush();
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
        var ids=List.of(item.getId());
        return view(item, totals(item.getOrganizationId(), ids).get(item.getId()), !balances.lowAmong(item.getOrganizationId(), ids).isEmpty());
    }
    private CatalogDtos.View view(Item item, BigDecimal onHand) { return view(item, onHand, false); }
    private CatalogDtos.View view(Item item, BigDecimal onHand, boolean low) {
        return new CatalogDtos.View(item.getId(), item.name, item.sku, item.unit, item.archived, item.getVersion(),
                Quantities.text(onHand == null ? BigDecimal.ZERO : onHand), low);
    }

    /** Sets or clears the minimum for an item at one of its active locations. Owners and Managers. */
    public void setMinimum(UUID orgId, UUID itemId, UUID locationId, BigDecimal value) {
        access.lock(orgId, OWNER, MANAGER);
        var item=find(orgId, itemId);
        editable(item);
        var placement=placements.findByOrganizationIdAndItemIdAndLocationId(orgId, itemId, locationId).filter(found -> found.active)
                .orElseThrow(() -> ApiException.conflict("NOT_PLACED", "Add this location to the item before setting a minimum."));
        placement.minimumQuantity = value == null ? null : Quantities.positive(item.unit, value);
        placements.flush();
        var name=locations.names(orgId, List.of(locationId)).get(locationId);
        var metadata=new LinkedHashMap<String, String>();
        metadata.put("name", item.name);
        metadata.put("location", Objects.toString(name, ""));
        metadata.put("unit", item.unit.name());
        if (placement.minimumQuantity != null) metadata.put("minimum", Quantities.text(placement.minimumQuantity));
        record(orgId, "MINIMUM_SET", "ITEM", itemId, metadata);
    }
    private Map<UUID, BigDecimal> totals(UUID orgId, Collection<UUID> itemIds) {
        var totals=new HashMap<UUID, BigDecimal>();
        if (!itemIds.isEmpty()) balances.totals(orgId, itemIds).forEach(row -> totals.put((UUID) row[0], (BigDecimal) row[1]));
        return totals;
    }
    private CatalogDtos.PlacementView placementView(ItemPlacement placement, String name) {
        return new CatalogDtos.PlacementView(placement.getId(), placement.itemId, placement.locationId, name);
    }
    private Map<String, String> metadata(Item item) { return Map.of("name", item.name, "sku", Objects.toString(item.sku, ""), "unit", item.unit.name()); }
    private void record(UUID orgId, String action, String type, UUID id, Map<String, String> metadata) {
        var actor=access.actor(); audit.record(orgId, actor.userId(), actor.displayName(), action, type, id, metadata);
    }
}
