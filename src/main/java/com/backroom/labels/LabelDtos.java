package com.backroom.labels;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LabelDtos {
    private LabelDtos() {}

    public enum TargetType {
        ITEM,
        LOCATION
    }

    public enum Layout {
        A4_21,
        LETTER_10,
        SINGLE
    }

    public enum ScanStatus {
        ITEM,
        LOCATION,
        REVOKED,
        OTHER_ORGANIZATION,
        NOT_FOUND,
        NOT_REGISTERED,
        UNSUPPORTED
    }

    public record Target(@NotNull TargetType type, @NotNull UUID id) {}

    public record BarcodeInput(
            @NotBlank @Size(max = 40) String value,
            @Size(max = 20) String format) {}

    public record Version(@NotNull Long expectedVersion) {}

    public record ScanInput(
            @NotBlank @Size(max = 500) String value,
            @Size(max = 20) String format) {}

    public record LabelRequest(
            @NotEmpty @Size(max = 105) List<@Valid @NotNull Target> targets,
            @NotNull Layout layout,
            @Min(1) @Max(21) Integer startPosition) {}

    /** display is what people read and type: 7K4Q-2M9X-QA for QR codes, the UPC/EAN digits for barcodes. */
    public record CodeView(
            UUID id,
            ResourceCode.Kind kind,
            String display,
            String url,
            TargetType targetType,
            UUID targetId,
            ResourceCode.Status status,
            Instant createdAt,
            Long version) {}

    /** Result of reading a code. Only ITEM and LOCATION carry resource details; other statuses explain what to do. */
    public record ScanResult(
            ScanStatus status,
            TargetType targetType,
            UUID id,
            String name,
            String parentName,
            String sku,
            String unit,
            boolean archived,
            String code,
            UUID organizationId,
            String organizationName,
            boolean canRegister) {
        static ScanResult of(ScanStatus status) {
            return new ScanResult(status, null, null, null, null, null, null, false, null, null, null, false);
        }
    }

    /** Where a printed QR link leads, for the phone-camera flow. */
    public record LinkView(ScanStatus status, UUID organizationId, TargetType targetType, UUID id) {}

    public record Settings(String publicBaseUrl, boolean localAddress, int maxLabels) {}
}
