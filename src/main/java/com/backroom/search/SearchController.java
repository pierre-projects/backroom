package com.backroom.search;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/search")
class SearchController {
    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    SearchService.Results search(@PathVariable UUID orgId, @RequestParam(defaultValue = "") String q) {
        return search.search(orgId, q);
    }
}
