package com.polymind.web;

import com.polymind.tenancy.ApiKey;
import com.polymind.tools.SearchResult;
import com.polymind.tools.WebSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct web-search endpoint ({@code POST /v1/tools/web_search}) — access pattern #3 (§8.2).
 * Returns {@code text + sources[] + cache_hit} with no generation; visible in Swagger UI.
 */
@RestController
@RequestMapping("/v1/tools")
@Tag(name = "Tools: Web Search", description = "Direct web search (no generation)")
public class WebSearchController {

    private final WebSearchService webSearch;

    public WebSearchController(WebSearchService webSearch) {
        this.webSearch = webSearch;
    }

    @PostMapping("/web_search")
    @Operation(summary = "Run a web search and return results + sources")
    public SearchResult search(@RequestBody WebSearchRequest req) {
        return webSearch.searchForKey(currentKeyId(), req.query(), req.days());
    }

    private String currentKeyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            return key.id();
        }
        return "anonymous";
    }

    public record WebSearchRequest(String query, Integer days) {}
}
