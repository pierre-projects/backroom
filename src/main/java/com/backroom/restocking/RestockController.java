package com.backroom.restocking;

import com.backroom.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/requests")
class RestockController {
    private final RestockService restock;

    RestockController(RestockService restock) {
        this.restock = restock;
    }

    @GetMapping
    PageResponse<RestockDtos.RequestView> list(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "OPEN") RestockDtos.Filter filter,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return restock.list(orgId, filter, itemId, locationId, page, size);
    }

    @GetMapping("/summary")
    RestockDtos.Summary summary(@PathVariable UUID orgId) {
        return restock.summary(orgId);
    }

    @GetMapping("/{id}")
    RestockDtos.RequestView get(@PathVariable UUID orgId, @PathVariable UUID id) {
        return restock.get(orgId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RestockDtos.RequestView submit(@PathVariable UUID orgId, @Valid @RequestBody RestockDtos.Submit input) {
        return restock.submit(orgId, input);
    }

    @PostMapping("/{id}/approve")
    RestockDtos.RequestView approve(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody RestockDtos.Approve input) {
        return restock.approve(orgId, id, input);
    }

    @PostMapping("/{id}/reject")
    RestockDtos.RequestView reject(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody RestockDtos.Reason input) {
        return restock.reject(orgId, id, input);
    }

    @PostMapping("/{id}/cancel")
    RestockDtos.RequestView cancel(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody RestockDtos.Version input) {
        return restock.cancel(orgId, id, input);
    }

    @PostMapping("/{id}/close-short")
    RestockDtos.RequestView closeShort(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody RestockDtos.Reason input) {
        return restock.closeShort(orgId, id, input);
    }

    /** 201 for a new delivery; 200 when a retry returns the result already recorded. */
    @PostMapping("/{id}/receipts")
    ResponseEntity<RestockDtos.RequestView> receive(
            @PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody RestockDtos.Receive input) {
        var view = restock.receive(orgId, id, input);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }
}
