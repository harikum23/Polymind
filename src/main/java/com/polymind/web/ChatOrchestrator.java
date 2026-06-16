package com.polymind.web;

import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;

import java.util.function.Consumer;

/**
 * Internal orchestration seam for the web edge. Step 2 implements this as a thin pass-through to
 * the inference engine; step 3 routes the model, step 5 injects knowledge, step 6 runs the agent
 * loop — all behind this same interface so controllers never change.
 */
public interface ChatOrchestrator {

    /** Resolve + stream a chat completion. {@code resolvedModel} is reported back for the response body. */
    String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk);

    /** Resolve + run a non-streaming chat completion. Returns the resolved model + aggregated result. */
    record ChatOutcome(String resolvedModel, String content, String finishReason, ChatChunk.Usage usage) {}

    ChatOutcome chat(ChatRequest request);

    EmbeddingResult embed(EmbeddingRequest request);
}
