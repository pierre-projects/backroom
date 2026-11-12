package com.backroom.inventory;
import com.backroom.shared.PageResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
class LocationContentsController {
    private final CatalogService catalog;
    LocationContentsController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/api/v1/organizations/{orgId}/locations/{locationId}/items")
    PageResponse<CatalogDtos.View> contents(@PathVariable UUID orgId, @PathVariable UUID locationId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return catalog.atLocation(orgId, locationId, page, size);
    }
}
