package com.polymind.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/** OpenAI-compatible {@code GET /v1/models} payload. Lists concrete models and category aliases. */
@Schema(name = "ModelsResponse")
public record ModelsResponse(String object, List<ModelEntry> data) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelEntry(
            String id,
            String object,
            @Schema(description = "polymind:model | polymind:alias") String type,
            @Schema(description = "owner") String ownedBy,
            @Schema(description = "Capability metadata: engine, ctx, supports_tools, scores")
            Map<String, Object> capabilities) {
    }
}
