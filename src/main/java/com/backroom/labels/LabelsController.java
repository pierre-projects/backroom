package com.backroom.labels;

import com.backroom.shared.error.InvalidRequestException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
class LabelsController {
    private final CodeService codes;
    private final ScanService scans;
    private final LabelPdfService pdfs;
    private final PublicUrl publicUrl;

    LabelsController(CodeService codes, ScanService scans, LabelPdfService pdfs, PublicUrl publicUrl) {
        this.codes = codes;
        this.scans = scans;
        this.pdfs = pdfs;
        this.publicUrl = publicUrl;
    }

    @GetMapping("/codes")
    List<LabelDtos.CodeView> list(
            @PathVariable UUID orgId,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID locationId) {
        if ((itemId == null) == (locationId == null))
            throw new InvalidRequestException("Choose an item or a location.");
        return itemId != null
                ? codes.list(orgId, LabelDtos.TargetType.ITEM, itemId)
                : codes.list(orgId, LabelDtos.TargetType.LOCATION, locationId);
    }

    @PostMapping("/codes")
    @ResponseStatus(HttpStatus.CREATED)
    LabelDtos.CodeView ensureQr(@PathVariable UUID orgId, @Valid @RequestBody LabelDtos.Target target) {
        return codes.ensureQr(orgId, target);
    }

    @PostMapping("/items/{itemId}/barcodes")
    @ResponseStatus(HttpStatus.CREATED)
    LabelDtos.CodeView registerBarcode(
            @PathVariable UUID orgId, @PathVariable UUID itemId, @Valid @RequestBody LabelDtos.BarcodeInput input) {
        return codes.registerBarcode(orgId, itemId, input);
    }

    @PostMapping("/codes/{codeId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID codeId, @Valid @RequestBody LabelDtos.Version input) {
        codes.revoke(orgId, codeId, input.expectedVersion());
    }

    @PostMapping("/scan-resolutions")
    LabelDtos.ScanResult resolve(@PathVariable UUID orgId, @Valid @RequestBody LabelDtos.ScanInput input) {
        return scans.resolve(orgId, input);
    }

    @GetMapping("/labels/settings")
    LabelDtos.Settings settings(@PathVariable UUID orgId) {
        return new LabelDtos.Settings(publicUrl.base(), publicUrl.local(), LabelPdfService.MAX_LABELS);
    }

    @PostMapping("/labels.pdf")
    ResponseEntity<byte[]> labels(@PathVariable UUID orgId, @Valid @RequestBody LabelDtos.LabelRequest request) {
        return pdf(pdfs.labels(orgId, request), "backroom-labels.pdf");
    }

    @GetMapping("/labels/calibration.pdf")
    ResponseEntity<byte[]> calibration(
            @PathVariable UUID orgId, @RequestParam(defaultValue = "A4_21") LabelDtos.Layout layout) {
        return pdf(
                pdfs.calibration(orgId, layout),
                "backroom-calibration-" + layout.name().toLowerCase() + ".pdf");
    }

    private static ResponseEntity<byte[]> pdf(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(body);
    }
}
