package com.backroom.locations;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class LocationDtos {
    private LocationDtos() {}
    public record Input(@NotBlank @Size(max=120) String name, @NotNull LocationType type, UUID parentId) {}
    public record Edit(@NotBlank @Size(max=120) String name, @NotNull LocationType type, UUID parentId,
                       @NotNull Long expectedVersion) {}
    public record Version(@NotNull Long expectedVersion) {}
    /** One step of the path from the top-level room down to (not including) a location. */
    public record Crumb(UUID id, String name, LocationType type) {}
    /**
     * ancestors: outermost first, filled when one location is read. childCount and itemCount: active child locations
     * and active placements of unarchived items, filled in lists. Otherwise empty or null.
     */
    public record View(UUID id, String name, LocationType type, UUID parentId, boolean archived, Long version,
                       java.util.List<Crumb> ancestors, Long childCount, Long itemCount) {}
}
