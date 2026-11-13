package com.backroom.inventory;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/items/{id}/photo")
class ItemPhotoController {
    private final ItemPhotoService photos;
    ItemPhotoController(ItemPhotoService photos) { this.photos = photos; }

    /** The raw JPEG body is read with a hard cap, so an oversized upload is refused without buffering it. */
    @PutMapping
    ItemPhotoService.Saved set(@PathVariable UUID orgId, @PathVariable UUID id, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > ItemPhotoService.MAX_BYTES) throw ItemPhotoService.tooLarge();
        var bytes = request.getInputStream().readNBytes(ItemPhotoService.MAX_BYTES + 1);
        if (bytes.length > ItemPhotoService.MAX_BYTES) throw ItemPhotoService.tooLarge();
        return photos.set(orgId, id, request.getContentType(), bytes);
    }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID orgId, @PathVariable UUID id) { photos.remove(orgId, id); }

    /** URLs carry ?v=photoVersion, so the bytes behind a URL never change and can be cached privately for a long time. */
    @GetMapping
    ResponseEntity<byte[]> get(@PathVariable UUID orgId, @PathVariable UUID id) {
        var photo = photos.get(orgId, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                .eTag("\"" + photo.version() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.bytes());
    }
}
