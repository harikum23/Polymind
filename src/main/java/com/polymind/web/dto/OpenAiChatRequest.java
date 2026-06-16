package com.polymind.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat completion request. Polymind reads the {@code model} control knob and
 * the {@code metadata} extension fields; unknown fields are ignored so stock OpenAI clients work.
 */
@Schema(name = "ChatCompletionRequest", description = "OpenAI-compatible chat completion request")
public record OpenAiChatRequest(

        @Schema(description = "Concrete model id (force), category alias (chat/code/math/reasoning), or 'auto'",
                example = "auto", defaultValue = "auto")
        String model,

        @NotEmpty
        @Schema(description = "Conversation so far")
        List<Message> messages,

        @Schema(description = "Sampling temperature", example = "0.7")
        Double temperature,

        @JsonProperty("top_p")
        @Schema(description = "Nucleus sampling")
        Double topP,

        @JsonProperty("max_tokens")
        @Schema(description = "Max tokens to generate")
        Integer maxTokens,

        @Schema(description = "Stream the response as Server-Sent Events", defaultValue = "false")
        Boolean stream,

        @Schema(description = "Tool/function definitions for the agent loop")
        List<Map<String, Object>> tools,

        @Schema(description = """
                Polymind extensions (ignored by stock OpenAI clients):
                - task: explicit task hint (chat/code/math/reasoning)
                - knowledge_pack: RAG pack name
                - web_search: true to ground the answer in live search
                - force: true to forbid routing fallback""")
        Map<String, Object> metadata) {

    @Schema(name = "ChatMessage")
    public record Message(
            @Schema(example = "user") String role,
            @Schema(example = "Explain virtual threads") String content) {
    }
}
