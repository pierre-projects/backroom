package com.backroom.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock command inputs and views. Every command carries an operationKey chosen by the client;
 * retrying with the same key and the same contents returns the original result instead of applying it twice.
 * Quantities are exact decimals; views return them as plain strings (for example "12.5").
 */
public final class StockDtos {
    private StockDtos() {}

    public enum RemovalReason {
        USAGE,
        WASTE,
        SALE
    }

    public record Receipt(
            @NotNull UUID operationKey,
            @NotNull UUID itemId,
            @NotNull UUID locationId,
            @NotNull BigDecimal quantity,
            @Size(max = 120) String reference,
            @Size(max = 500) String note) {
        String fingerprint() {
            return join("RECEIPT", itemId, locationId, plain(quantity), reference, note);
        }
    }

    public record Opening(
            @NotNull UUID operationKey,
            @NotNull UUID itemId,
            @NotNull UUID locationId,
            @NotNull BigDecimal quantity,
            @Size(max = 500) String note) {
        String fingerprint() {
            return join("OPENING", itemId, locationId, plain(quantity), note);
        }
    }

    public record Removal(
            @NotNull UUID operationKey,
            @NotNull UUID itemId,
            @NotNull UUID locationId,
            @NotNull BigDecimal quantity,
            @NotNull RemovalReason reason,
            @Size(max = 500) String note) {
        String fingerprint() {
            return join(reason.name(), itemId, locationId, plain(quantity), note);
        }
    }

    public record Transfer(
            @NotNull UUID operationKey,
            @NotNull UUID itemId,
            @NotNull UUID fromLocationId,
            @NotNull UUID toLocationId,
            @NotNull BigDecimal quantity,
            @Size(max = 500) String note) {
        String fingerprint() {
            return join("TRANSFER", itemId, fromLocationId, toLocationId, plain(quantity), note);
        }
    }

    public record Count(
            @NotNull UUID operationKey,
            @NotNull UUID itemId,
            @NotNull UUID locationId,
            @NotNull BigDecimal countedQuantity,
            @NotNull Long expectedBalanceVersion,
            @Size(max = 500) String note) {
        String fingerprint() {
            return join("COUNT", itemId, locationId, plain(countedQuantity), expectedBalanceVersion, note);
        }
    }

    public record Reverse(
            @NotNull UUID operationKey, @Size(max = 500) String note) {
        String fingerprint(UUID operationId) {
            return join("REVERSAL", operationId, note);
        }
    }

    public record MovementView(
            UUID id,
            UUID operationId,
            StockOperationType operationType,
            UUID itemId,
            UUID locationId,
            String locationName,
            String parentName,
            String quantityChange,
            String balanceAfter,
            String actorName,
            Instant occurredAt,
            String reference,
            String note,
            UUID reversesOperationId,
            boolean reversed) {}

    public record CountResult(String countedQuantity, String recordedQuantity, String difference) {}

    public record OperationView(
            UUID id,
            StockOperationType type,
            UUID itemId,
            String actorName,
            Instant occurredAt,
            UUID reversesOperationId,
            List<MovementView> movements,
            CountResult count,
            boolean replayed) {}

    public record BalanceView(
            UUID itemId,
            UUID locationId,
            String locationName,
            String parentName,
            String quantity,
            Long version,
            boolean hasHistory,
            String minimum,
            boolean low) {}

    public record LowStockView(
            UUID itemId,
            String itemName,
            String sku,
            Unit unit,
            UUID locationId,
            String locationName,
            String parentName,
            String quantity,
            String minimum,
            String shortfall) {}

    public record Minimum(BigDecimal minimum) {}

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String join(Object... parts) {
        var text = new StringBuilder();
        for (var part : parts)
            text.append(part == null ? "" : part.toString().strip()).append('\u001f');
        return text.toString();
    }
}
