package com.backroom.labels;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Phone-camera entry point: a printed QR link opens /s/{token}, and the web app asks here where it leads. */
@RestController
class CodeLinkController {
    private final ScanService scans;

    CodeLinkController(ScanService scans) {
        this.scans = scans;
    }

    @GetMapping("/api/v1/codes/{token}")
    LabelDtos.LinkView open(@PathVariable String token) {
        return scans.open(token);
    }
}
