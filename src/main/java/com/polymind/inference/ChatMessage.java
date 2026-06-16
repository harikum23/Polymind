package com.polymind.inference;

import java.util.List;

/**
 * A single OpenAI-style chat message. {@code toolCalls} / {@code toolCallId} support the
 * agent tool-use loop; they are null for plain chat.
 */
public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name) {

    public static ChatMessage of(String role, String content) {
        return new ChatMessage(role, content, null, null, null);
    }

    public record ToolCall(String id, String type, FunctionCall function) {}

    public record FunctionCall(String name, String arguments) {}
}
