package com.polymind.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** OpenAI-compatible chat completion response (non-streaming and streaming chunk share this shape). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ChatCompletionResponse")
public record OpenAiChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
            int index,
            Message message,
            Delta delta,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record Message(String role, String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {}

    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }
}
