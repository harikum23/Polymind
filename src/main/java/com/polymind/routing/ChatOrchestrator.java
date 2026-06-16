package com.polymind.routing;

import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;

import java.util.function.Consumer;

/**
 * Orchestration seam consumed by the web edge. Owned by {@code routing} (the brain). The
 * routing-aware implementation resolves the {@code model} knob and dispatches to an engine;
 * later steps layer knowledge injection and the agent loop behind this same interface.
 */
public interface ChatOrchestrator {

    /** Resolve + stream a chat completion; returns the resolved model id for the response body. */
    String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk);

    /** Resolve + run a non-streaming chat completion. */
    ChatOutcome chat(ChatRequest request);

    EmbeddingResult embed(EmbeddingRequest request);

    record ChatOutcome(String resolvedModel, String content, String finishReason, ChatChunk.Usage usage) {}
}
