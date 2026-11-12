package com.backroom.inventory;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}
    public record Input(@NotBlank @Size(max=160) String name, @Size(max=80) String sku, @NotNull Unit unit) {}
    public record Edit(@NotBlank @Size(max=160) String name, @Size(max=80) String sku, @NotNull Unit unit,
                       @NotNull Long expectedVersion) {}
    public record Version(@NotNull Long expectedVersion) {}
    public record View(UUID id, String name, String sku, Unit unit, boolean archived, Long version) {}
    public record PlacementView(UUID id, UUID itemId, UUID locationId, String locationName) {}
}
