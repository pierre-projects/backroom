package com.backroom.locations;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class LocationDtos {
    private LocationDtos() {}
    public record Input(@NotBlank @Size(max=120) String name, @NotNull LocationType type, UUID parentId) {}
    public record Edit(@NotBlank @Size(max=120) String name, @NotNull LocationType type, UUID parentId,
                       @NotNull Long expectedVersion) {}
    public record Version(@NotNull Long expectedVersion) {}
    public record View(UUID id, String name, LocationType type, UUID parentId, boolean archived, Long version) {}
}
