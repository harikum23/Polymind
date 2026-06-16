package com.polymind.inference;

/**
 * A streamed delta from an engine. {@code contentDelta} carries the incremental text;
 * {@code finishReason} is non-null only on the terminal chunk; {@code usage} (prompt/completion
 * tokens) is populated on the terminal chunk when the engine reports it.
 */
public record ChatChunk(
        String contentDelta,
        String finishReason,
        Usage usage,
        boolean done) {

    public static ChatChunk delta(String text) {
        return new ChatChunk(text, null, null, false);
    }

    public static ChatChunk terminal(String finishReason, Usage usage) {
        return new ChatChunk(null, finishReason, usage, true);
    }

    public record Usage(int promptTokens, int completionTokens) {
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
