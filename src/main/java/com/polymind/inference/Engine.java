package com.polymind.inference;

import java.util.function.Consumer;

/**
 * Inference engine port. Adapters (Ollama/vLLM/llama.cpp/remote) implement this. Polymind never
 * generates tokens itself — it orchestrates engines over HTTP.
 *
 * <p>Streaming uses a blocking callback ({@link #streamChat}) which, on virtual threads, scales to
 * thousands of concurrent SSE connections with simple imperative code (ARCHITECTURE.md §9).
 */
public interface Engine {

    /** Engine name as referenced by the model registry (e.g. {@code "ollama"}). */
    String name();

    /** Stream a chat completion, invoking {@code onChunk} for each delta until the terminal chunk. */
    void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk);

    /** Non-streaming chat completion (internally consumes the stream and aggregates). */
    ChatResult chat(ChatRequest request);

    /** Compute embeddings for the given inputs. */
    EmbeddingResult embed(EmbeddingRequest request);

    /** Cheap liveness probe used by health and resilience. */
    boolean isHealthy();
}
