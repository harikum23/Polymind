package com.polymind.inference;

import java.util.List;
import java.util.Map;

/**
 * Engine-facing chat request. This is the normalized, post-routing request handed to an
 * {@link Engine}: {@code model} is always a concrete, resolved engine model id by this point.
 */
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Boolean stream,
        List<Map<String, Object>> tools,
        Map<String, Object> extraOptions) {

    public ChatRequest withModel(String resolvedModel) {
        return new ChatRequest(resolvedModel, messages, temperature, topP, maxTokens, stream, tools, extraOptions);
    }

    public ChatRequest withMessages(List<ChatMessage> newMessages) {
        return new ChatRequest(model, newMessages, temperature, topP, maxTokens, stream, tools, extraOptions);
    }
}
