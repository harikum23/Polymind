package com.polymind.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "EmbeddingResponse")
public record OpenAiEmbeddingResponse(
        String object,
        List<Data> data,
        String model,
        Usage usage) {

    public record Data(String object, int index, float[] embedding) {}

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }
}
