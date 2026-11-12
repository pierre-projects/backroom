package com.backroom.search;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/search")
class SearchController {
    private final SearchService search;
    SearchController(SearchService search) { this.search=search; }
    @GetMapping
    SearchService.Results search(@PathVariable UUID orgId, @RequestParam(defaultValue="") String q) { return search.search(orgId, q); }
}
