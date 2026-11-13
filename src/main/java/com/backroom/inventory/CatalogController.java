package com.backroom.inventory;

import com.backroom.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/items")
class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    PageResponse<CatalogDtos.View> list(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "false") boolean lowStock,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.list(orgId, includeArchived, q, locationId, lowStock, sort, page, size);
    }

    @PutMapping("/{id}/placements/{locationId}/minimum")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setMinimum(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID locationId,
            @RequestBody StockDtos.Minimum input) {
        catalog.setMinimum(orgId, id, locationId, input.minimum());
    }

    @GetMapping("/{id}")
    CatalogDtos.View get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return catalog.get(orgId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CatalogDtos.View create(@PathVariable UUID orgId, @Valid @RequestBody CatalogDtos.Input input) {
        return catalog.create(orgId, input);
    }

    @PatchMapping("/{id}")
    CatalogDtos.View edit(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody CatalogDtos.Edit input) {
        return catalog.edit(orgId, id, input);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody CatalogDtos.Version input) {
        catalog.archive(orgId, id, input.expectedVersion());
    }

    @GetMapping("/{id}/placements")
    PageResponse<CatalogDtos.PlacementView> placements(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalog.placements(orgId, id, page, size);
    }

    @PutMapping("/{id}/placements/{locationId}")
    CatalogDtos.PlacementView place(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID locationId) {
        return catalog.place(orgId, id, locationId);
    }

    @DeleteMapping("/{id}/placements/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removePlacement(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID locationId) {
        catalog.removePlacement(orgId, id, locationId);
    }
}
