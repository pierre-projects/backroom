package com.backroom.locations;

import com.backroom.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/locations")
class LocationController {
    private final LocationService locations;

    LocationController(LocationService locations) {
        this.locations = locations;
    }

    @GetMapping
    PageResponse<LocationDtos.View> list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "false") boolean rootsOnly,
            @RequestParam(defaultValue = "false") boolean leafOnly,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return locations.list(orgId, parentId, rootsOnly, leafOnly, includeArchived, q, page, size);
    }

    @GetMapping("/{id}")
    LocationDtos.View get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return locations.get(orgId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LocationDtos.View create(@PathVariable UUID orgId, @Valid @RequestBody LocationDtos.Input input) {
        return locations.create(orgId, input);
    }

    @PatchMapping("/{id}")
    LocationDtos.View edit(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody LocationDtos.Edit input) {
        return locations.edit(orgId, id, input);
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody LocationDtos.Version input) {
        locations.archive(orgId, id, input.expectedVersion());
    }
}
