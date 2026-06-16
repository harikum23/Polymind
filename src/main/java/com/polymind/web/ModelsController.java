package com.polymind.web;

import com.polymind.web.dto.ModelsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OpenAI-compatible {@code GET /v1/models}: concrete models and category aliases. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Models", description = "List models and category aliases")
public class ModelsController {

    private final ModelCatalog catalog;

    public ModelsController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/models")
    @Operation(summary = "List available models and category aliases")
    public ModelsResponse list() {
        return new ModelsResponse("list", catalog.list());
    }
}
