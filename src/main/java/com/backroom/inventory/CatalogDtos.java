package com.backroom.inventory;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}
    public record Input(@NotBlank @Size(max=160) String name, @Size(max=80) String sku, @NotNull Unit unit) {}
    public record Edit(@NotBlank @Size(max=160) String name, @Size(max=80) String sku, @NotNull Unit unit,
                       @NotNull Long expectedVersion) {}
    public record Version(@NotNull Long expectedVersion) {}
    /** onHand is the total quantity across locations, or at one location when listing a location's items. */
    /** photoVersion is null when the item has no photo; otherwise it versions the photo URL. */
    public record View(UUID id, String name, String sku, Unit unit, boolean archived, Long version, String onHand, boolean low, Long photoVersion) {}
    public record PlacementView(UUID id, UUID itemId, UUID locationId, String locationName) {}
}
