package com.backroom.restocking;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Quantities in views are plain strings in the item's base unit, as in inventory ("12.5"). */
public final class RestockDtos {
    private RestockDtos() {}
    public enum Filter { NEEDS_REVIEW, TO_RECEIVE, MINE, OPEN, ALL }

    public record Submit(@NotNull UUID itemId, @NotNull UUID locationId, @NotNull BigDecimal quantity, @Size(max=500) String note) {}
    public record Approve(@NotNull Long expectedVersion, BigDecimal approvedQuantity, @Size(max=500) String note) {}
    public record Reason(@NotNull Long expectedVersion, @NotBlank @Size(max=500) String reason) {}
    public record Version(@NotNull Long expectedVersion) {}
    public record Receive(@NotNull UUID operationKey, @NotNull BigDecimal quantity, @Size(max=500) String note) {}

    public record ReceiptView(UUID id, String quantity, String receiverName, Instant receivedAt, UUID stockOperationId, boolean reversed) {}
    public record RequestView(UUID id, int number, String label, RequestStatus status,
                              UUID itemId, String itemName, String sku, String unit, boolean itemArchived,
                              UUID locationId, String locationName, String parentName,
                              String requested, String approved, String received, String remaining,
                              UUID requesterId, String requesterName, String note, Instant createdAt,
                              String decidedByName, Instant decidedAt, String decisionNote,
                              String closedByName, Instant closedAt, String closeReason,
                              Instant updatedAt, Long version, List<ReceiptView> receipts, boolean replayed) {}
    public record Summary(long needsReview, long toReceive) {}
}
