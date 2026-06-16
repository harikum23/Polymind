package com.polymind.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** OpenAI-compatible embeddings request. {@code input} accepts a string or array of strings. */
@Schema(name = "EmbeddingRequest")
public record OpenAiEmbeddingRequest(
        @Schema(example = "nomic-embed") String model,
        @Schema(description = "A string or array of strings to embed") Object input,
        @JsonProperty("encoding_format") String encodingFormat) {

    @SuppressWarnings("unchecked")
    public List<String> inputs() {
        if (input == null) {
            return List.of();
        }
        if (input instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(input));
    }
}
