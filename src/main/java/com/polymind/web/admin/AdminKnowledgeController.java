package com.polymind.web.admin;

import com.polymind.knowledge.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin knowledge-pack management ({@code /v1/admin/knowledge}). Index documents into a pack and
 * inspect counts. Retrieval at request time is activated via {@code metadata.knowledge_pack}.
 */
@RestController
@RequestMapping("/v1/admin/knowledge")
@Tag(name = "Admin: Knowledge", description = "Knowledge-pack indexing (admin only)")
public class AdminKnowledgeController {

    private final KnowledgeService knowledge;

    public AdminKnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/{pack}")
    @Operation(summary = "Pack status (enabled + chunk count)")
    public Map<String, Object> status(@PathVariable String pack) {
        return Map.of("pack", pack, "enabled", knowledge.isEnabled(), "chunks", knowledge.count(pack));
    }

    @PostMapping("/{pack}/index")
    @Operation(summary = "Index one document into a pack")
    public Map<String, Object> index(@PathVariable String pack, @RequestBody IndexRequest req) {
        int chunks = knowledge.index(pack, req.source(), req.text());
        return Map.of("pack", pack, "indexedChunks", chunks);
    }

    @PostMapping("/{pack}/reindex")
    @Operation(summary = "Re-index a pack from scratch")
    public Map<String, Object> reindex(@PathVariable String pack, @RequestBody List<DocumentDto> docs) {
        knowledge.reindex(pack, docs.stream()
                .map(d -> new KnowledgeService.Document(d.source(), d.text())).toList());
        return Map.of("pack", pack, "chunks", knowledge.count(pack));
    }

    public record IndexRequest(String source, String text) {}

    public record DocumentDto(String source, String text) {}
}
