package com.backroom.imports;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/imports/items")
class ImportController {
    static final String TEMPLATE = "﻿Item,SKU,Unit,Location,Starting quantity,Minimum\r\n"
            + "House espresso,COF-101,kg,Stockroom > Coffee shelf,12,4\r\n"
            + "\"Paper cups, 12 oz\",CUP-12,each,Stockroom > Dry goods > Cups bin,240,100\r\n"
            + "Oat milk,OAT-01,L,Coffee bar > Fridge,6,\r\n";

    private final ImportService imports;
    ImportController(ImportService imports) { this.imports = imports; }

    /** The raw CSV body is read with a hard cap, so an oversized file is refused without buffering it all. */
    @PostMapping("/preview")
    ImportService.Preview preview(@PathVariable UUID orgId, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > ImportService.MAX_BYTES) throw ImportService.tooLarge();
        var bytes = request.getInputStream().readNBytes(ImportService.MAX_BYTES + 1);
        if (bytes.length > ImportService.MAX_BYTES) throw ImportService.tooLarge();
        return imports.preview(orgId, new String(bytes, StandardCharsets.UTF_8));
    }

    @PostMapping
    ResponseEntity<ImportService.Result> apply(@PathVariable UUID orgId, @RequestBody ImportService.ApplyInput input) {
        var result = imports.apply(orgId, input);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/template.csv")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("backroom-import-template.csv").build().toString())
                .body(TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }
}
