package com.backroom.labels;

import static com.backroom.labels.LabelDtos.ScanStatus.ITEM;
import static com.backroom.labels.LabelDtos.ScanStatus.LOCATION;
import static com.backroom.labels.LabelDtos.ScanStatus.NOT_FOUND;
import static com.backroom.labels.LabelDtos.ScanStatus.NOT_REGISTERED;
import static com.backroom.labels.LabelDtos.ScanStatus.OTHER_ORGANIZATION;
import static com.backroom.labels.LabelDtos.ScanStatus.REVOKED;
import static com.backroom.labels.LabelDtos.ScanStatus.UNSUPPORTED;

import com.backroom.access.AccessPolicy;
import com.backroom.access.OrganizationService;
import com.backroom.access.Role;
import com.backroom.inventory.ItemQueries;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns whatever a camera or keyboard produced into a resource, or a clear reason why not.
 * Membership is checked before any resource detail is returned; a code identifies, it never authorizes.
 */
@Service
@Transactional(readOnly = true)
public class ScanService {
    private final ResourceCodeRepository codes;
    private final ItemQueries items;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final OrganizationService organizations;
    private final PublicUrl publicUrl;

    ScanService(
            ResourceCodeRepository codes,
            ItemQueries items,
            LocationQueries locations,
            AccessPolicy access,
            OrganizationService organizations,
            PublicUrl publicUrl) {
        this.codes = codes;
        this.items = items;
        this.locations = locations;
        this.access = access;
        this.organizations = organizations;
        this.publicUrl = publicUrl;
    }

    public LabelDtos.ScanResult resolve(UUID orgId, LabelDtos.ScanInput input) {
        var role = access.require(orgId);
        var raw = input.value().strip();
        String tokenText = raw;
        if (raw.contains("://")) {
            tokenText = publicUrl.tokenFrom(raw);
            if (tokenText == null) return LabelDtos.ScanResult.of(UNSUPPORTED);
        }
        var token = raw.chars().allMatch(Character::isDigit) && !raw.contains("://")
                ? java.util.Optional.<String>empty()
                : Tokens.parse(tokenText);
        if (token.isPresent()) return fromToken(orgId, token.get());

        var candidates = Gtin.candidates(raw, input.format());
        if (candidates.isEmpty()) return LabelDtos.ScanResult.of(UNSUPPORTED);
        var registered = codes.findByOrganizationIdAndKindAndValueInAndStatus(
                orgId, ResourceCode.Kind.MANUFACTURER, candidates, ResourceCode.Status.ACTIVE);
        if (!registered.isEmpty()) return item(orgId, registered.getFirst());
        return new LabelDtos.ScanResult(
                NOT_REGISTERED,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                Gtin.display(candidates.getFirst()),
                null,
                null,
                role != Role.EMPLOYEE);
    }

    /** For a QR link opened with the phone's own camera: which organization and page it belongs to. */
    public LabelDtos.LinkView open(String rawToken) {
        var token = Tokens.parse(rawToken).orElseThrow(ApiException::notFound);
        var code = codes.findByKindAndValue(ResourceCode.Kind.GENERATED, token).orElseThrow(ApiException::notFound);
        if (!access.isActiveMember(code.getOrganizationId())) throw ApiException.notFound();
        if (!code.active()) return new LabelDtos.LinkView(REVOKED, code.getOrganizationId(), null, null);
        var item = code.itemId != null;
        return new LabelDtos.LinkView(
                item ? ITEM : LOCATION,
                code.getOrganizationId(),
                item ? LabelDtos.TargetType.ITEM : LabelDtos.TargetType.LOCATION,
                item ? code.itemId : code.locationId);
    }

    private LabelDtos.ScanResult fromToken(UUID orgId, String token) {
        var found = codes.findByKindAndValue(ResourceCode.Kind.GENERATED, token);
        if (found.isEmpty()) return LabelDtos.ScanResult.of(NOT_FOUND);
        var code = found.get();
        if (!code.getOrganizationId().equals(orgId)) {
            if (!access.isActiveMember(code.getOrganizationId())) return LabelDtos.ScanResult.of(NOT_FOUND);
            var other = organizations.get(code.getOrganizationId());
            return new LabelDtos.ScanResult(
                    OTHER_ORGANIZATION,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    Tokens.format(token),
                    other.id(),
                    other.name(),
                    false);
        }
        if (!code.active()) {
            return new LabelDtos.ScanResult(
                    REVOKED, null, null, null, null, null, null, false, Tokens.format(token), null, null, false);
        }
        if (code.itemId != null) return item(orgId, code);
        var location = locations.require(orgId, code.locationId);
        return new LabelDtos.ScanResult(
                LOCATION,
                LabelDtos.TargetType.LOCATION,
                location.id(),
                location.name(),
                location.parentName(),
                null,
                null,
                location.archived(),
                Tokens.format(token),
                orgId,
                null,
                false);
    }

    private LabelDtos.ScanResult item(UUID orgId, ResourceCode code) {
        var item = items.require(orgId, code.itemId);
        var display = code.kind == ResourceCode.Kind.GENERATED ? Tokens.format(code.value) : Gtin.display(code.value);
        return new LabelDtos.ScanResult(
                ITEM,
                LabelDtos.TargetType.ITEM,
                item.id(),
                item.name(),
                null,
                item.sku(),
                item.unit().name(),
                item.archived(),
                display,
                orgId,
                null,
                false);
    }
}
