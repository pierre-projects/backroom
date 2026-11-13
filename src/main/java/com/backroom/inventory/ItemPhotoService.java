package com.backroom.inventory;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.DomainException;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.error.PayloadTooLargeException;
import com.backroom.shared.error.UnsupportedMediaTypeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One JPEG per item. The browser resizes before upload; the server only checks what it receives: JPEG signature,
 * declared dimensions (read from the header before decoding, so a small file can't claim a huge image), then a full
 * decode to prove the bytes are a real image.
 */
@Service
@Transactional
public class ItemPhotoService {
    public static final int MAX_BYTES = 600 * 1024;
    static final int MAX_SIDE = 2048;

    public record Photo(byte[] bytes, String contentType, long version) {}

    public record Saved(long photoVersion, int width, int height) {}

    private final ItemPhotoRepository photos;
    private final ItemRepository items;
    private final AccessPolicy access;
    private final AuditService audit;
    private final Clock clock;

    ItemPhotoService(
            ItemPhotoRepository photos, ItemRepository items, AccessPolicy access, AuditService audit, Clock clock) {
        this.photos = photos;
        this.items = items;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public Saved set(UUID orgId, UUID itemId, String contentType, byte[] bytes) {
        access.lock(orgId, OWNER, MANAGER);
        var item = items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(NotFoundException::new);
        if (item.archived) throw new ConflictException("ARCHIVED", "Archived items cannot be changed.");
        var type = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!type.equals("image/jpeg"))
            throw new UnsupportedMediaTypeException("UNSUPPORTED_IMAGE", "Upload the photo as a JPEG.");
        if (bytes.length > MAX_BYTES) throw tooLarge();
        var size = inspect(bytes);
        var photo = photos.findByOrganizationIdAndItemId(orgId, itemId).orElseGet(() -> new ItemPhoto(orgId, itemId));
        var actor = access.actor();
        photo.contentType = type;
        photo.bytes = bytes;
        photo.width = size[0];
        photo.height = size[1];
        photo.updatedBy = actor.userId();
        // Strictly increasing, so a replaced photo always gets a new URL.
        var now = clock.instant();
        photo.updatedAt =
                photo.updatedAt != null && !now.isAfter(photo.updatedAt) ? photo.updatedAt.plusMillis(1) : now;
        photos.saveAndFlush(photo);
        audit.record(
                orgId,
                actor.userId(),
                actor.displayName(),
                "ITEM_PHOTO_SET",
                "ITEM",
                itemId,
                Map.of("name", item.name, "width", String.valueOf(size[0]), "height", String.valueOf(size[1])));
        return new Saved(photo.photoVersion(), size[0], size[1]);
    }

    public void remove(UUID orgId, UUID itemId) {
        access.lock(orgId, OWNER, MANAGER);
        var item = items.findByOrganizationIdAndId(orgId, itemId).orElseThrow(NotFoundException::new);
        var photo = photos.findByOrganizationIdAndItemId(orgId, itemId).orElse(null);
        if (photo == null) return;
        photos.delete(photo);
        photos.flush();
        var actor = access.actor();
        audit.record(
                orgId,
                actor.userId(),
                actor.displayName(),
                "ITEM_PHOTO_REMOVED",
                "ITEM",
                itemId,
                Map.of("name", item.name));
    }

    @Transactional(readOnly = true)
    public Photo get(UUID orgId, UUID itemId) {
        access.require(orgId);
        var photo = photos.findByOrganizationIdAndItemId(orgId, itemId).orElseThrow(NotFoundException::new);
        return new Photo(photo.bytes, photo.contentType, photo.photoVersion());
    }

    /** Photo versions for a page of items; items without a photo are absent. */
    @Transactional(readOnly = true)
    Map<UUID, Long> versions(UUID orgId, Collection<UUID> itemIds) {
        var result = new HashMap<UUID, Long>();
        if (!itemIds.isEmpty())
            photos.versions(orgId, itemIds)
                    .forEach(row -> result.put((UUID) row[0], ((Instant) row[1]).toEpochMilli()));
        return result;
    }

    static PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException("PHOTO_TOO_LARGE", "That photo is too large. Use one under 600 KB.");
    }

    private static InvalidRequestException invalid() {
        return new InvalidRequestException("INVALID_IMAGE", "That file isn't a readable JPEG photo.");
    }
    /** Returns {width, height} of a valid JPEG within the size limits. */
    private static int[] inspect(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8 || (bytes[2] & 0xFF) != 0xFF)
            throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1) throw invalid();
                if (width > MAX_SIDE || height > MAX_SIDE) {
                    throw new InvalidRequestException(
                            "IMAGE_TOO_BIG", "Photos can be at most " + MAX_SIDE + " pixels on a side.");
                }
                if (reader.read(0) == null) throw invalid();
                return new int[] {width, height};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof DomainException domain) throw domain;
            throw invalid();
        }
    }
}
