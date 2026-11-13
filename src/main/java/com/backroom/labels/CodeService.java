package com.backroom.labels;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.inventory.ItemQueries;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and revokes codes. Writes take the organization lock (like other administrative changes), which also
 * keeps two simultaneous "print label" clicks from creating two QR codes for one resource.
 */
@Service
@Transactional
public class CodeService {
    private final ResourceCodeRepository codes;
    private final ItemQueries items;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final AuditService audit;
    private final PublicUrl publicUrl;

    CodeService(
            ResourceCodeRepository codes,
            ItemQueries items,
            LocationQueries locations,
            AccessPolicy access,
            AuditService audit,
            PublicUrl publicUrl) {
        this.codes = codes;
        this.items = items;
        this.locations = locations;
        this.access = access;
        this.audit = audit;
        this.publicUrl = publicUrl;
    }

    @Transactional(readOnly = true)
    public List<LabelDtos.CodeView> list(UUID orgId, LabelDtos.TargetType type, UUID targetId) {
        access.require(orgId);
        requireTarget(orgId, type, targetId);
        var found = type == LabelDtos.TargetType.ITEM
                ? codes.findByOrganizationIdAndItemIdOrderByCreatedAtAscIdAsc(orgId, targetId)
                : codes.findByOrganizationIdAndLocationIdOrderByCreatedAtAscIdAsc(orgId, targetId);
        return found.stream().map(this::view).toList();
    }

    public LabelDtos.CodeView ensureQr(UUID orgId, LabelDtos.Target target) {
        access.lock(orgId, OWNER, MANAGER);
        return view(ensureQrLocked(orgId, target));
    }

    /** Returns the resource's active QR code, creating one if needed. Caller holds the organization lock. */
    ResourceCode ensureQrLocked(UUID orgId, LabelDtos.Target target) {
        var archived = requireTarget(orgId, target.type(), target.id());
        var itemId = target.type() == LabelDtos.TargetType.ITEM ? target.id() : null;
        var locationId = target.type() == LabelDtos.TargetType.LOCATION ? target.id() : null;
        var existing = codes.findActiveQr(orgId, itemId, locationId);
        if (existing.isPresent()) return existing.get();
        if (archived) throw ApiException.conflict("ARCHIVED", "Archived items and locations can't get new labels.");
        // 50 random bits: a collision is vanishingly unlikely, and the unique index would reject one rather than reuse
        // it.
        var code = codes.saveAndFlush(new ResourceCode(
                orgId,
                ResourceCode.Kind.GENERATED,
                Tokens.generate(),
                itemId,
                locationId,
                access.actor().userId()));
        record(orgId, "CODE_CREATED", code);
        return code;
    }

    public LabelDtos.CodeView registerBarcode(UUID orgId, UUID itemId, LabelDtos.BarcodeInput input) {
        access.lock(orgId, OWNER, MANAGER);
        var item = items.require(orgId, itemId);
        if (item.archived()) throw ApiException.conflict("ARCHIVED", "Archived items can't get new barcodes.");
        var candidates = Gtin.candidates(input.value(), input.format());
        if (candidates.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BARCODE",
                    "That isn't a valid UPC or EAN barcode. Check the digits, including the last one.");
        }
        var active = codes.findByOrganizationIdAndKindAndValueInAndStatus(
                orgId, ResourceCode.Kind.MANUFACTURER, candidates, ResourceCode.Status.ACTIVE);
        for (var code : active) {
            if (itemId.equals(code.itemId)) return view(code);
            var holder = items.require(orgId, code.itemId);
            throw ApiException.conflict(
                    "BARCODE_IN_USE",
                    "This barcode is already registered to " + holder.name() + ". Remove it there first.");
        }
        var code = codes.saveAndFlush(new ResourceCode(
                orgId,
                ResourceCode.Kind.MANUFACTURER,
                candidates.getFirst(),
                itemId,
                null,
                access.actor().userId()));
        record(orgId, "BARCODE_REGISTERED", code);
        return view(code);
    }

    public void revoke(UUID orgId, UUID codeId, Long expectedVersion) {
        access.lock(orgId, OWNER, MANAGER);
        var code = codes.findByOrganizationIdAndId(orgId, codeId).orElseThrow(ApiException::notFound);
        code.checkVersion(expectedVersion);
        if (!code.active()) return;
        code.status = ResourceCode.Status.REVOKED;
        code.revokedAt = Instant.now();
        code.revokedBy = access.actor().userId();
        codes.flush();
        record(orgId, "CODE_REVOKED", code);
    }

    /** Throws 404 when the target is missing in this organization; returns whether it is archived. */
    boolean requireTarget(UUID orgId, LabelDtos.TargetType type, UUID id) {
        return type == LabelDtos.TargetType.ITEM
                ? items.require(orgId, id).archived()
                : locations.require(orgId, id).archived();
    }

    LabelDtos.CodeView view(ResourceCode code) {
        var generated = code.kind == ResourceCode.Kind.GENERATED;
        return new LabelDtos.CodeView(
                code.getId(),
                code.kind,
                generated ? Tokens.format(code.value) : Gtin.display(code.value),
                generated ? publicUrl.link(code.value) : null,
                code.itemId != null ? LabelDtos.TargetType.ITEM : LabelDtos.TargetType.LOCATION,
                code.itemId != null ? code.itemId : code.locationId,
                code.status,
                code.getCreatedAt(),
                code.getVersion());
    }

    private void record(UUID orgId, String action, ResourceCode code) {
        var actor = access.actor();
        var view = view(code);
        audit.record(
                orgId,
                actor.userId(),
                actor.displayName(),
                action,
                view.targetType().name(),
                view.targetId(),
                Map.of("code", view.display(), "kind", code.kind.name()));
    }
}
