package com.backroom.inventory;
import com.backroom.shared.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/items")
class CatalogController {
    private final CatalogService catalog;
    CatalogController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping
    PageResponse<CatalogDtos.View> list(@PathVariable UUID orgId, @RequestParam(defaultValue="false") boolean includeArchived,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return catalog.list(orgId, includeArchived, q, page, size);
    }
    @GetMapping("/{id}")
    CatalogDtos.View get(@PathVariable UUID orgId, @PathVariable UUID id) { return catalog.get(orgId, id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    CatalogDtos.View create(@PathVariable UUID orgId, @Valid @RequestBody CatalogDtos.Input input) { return catalog.create(orgId, input); }
    @PatchMapping("/{id}")
    CatalogDtos.View edit(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody CatalogDtos.Edit input) { return catalog.edit(orgId, id, input); }
    @PostMapping("/{id}/archive") @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody CatalogDtos.Version input) {
        catalog.archive(orgId, id, input.expectedVersion());
    }
    @GetMapping("/{id}/placements")
    PageResponse<CatalogDtos.PlacementView> placements(@PathVariable UUID orgId, @PathVariable UUID id,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return catalog.placements(orgId, id, page, size);
    }
    @PutMapping("/{id}/placements/{locationId}")
    CatalogDtos.PlacementView place(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID locationId) {
        return catalog.place(orgId, id, locationId);
    }
    @DeleteMapping("/{id}/placements/{locationId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePlacement(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID locationId) {
        catalog.removePlacement(orgId, id, locationId);
    }
}
