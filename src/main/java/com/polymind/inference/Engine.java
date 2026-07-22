package com.polymind.inference;

import java.util.Set;
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

    /**
     * Model ids this engine can currently serve (ARCHITECTURE.md §4.2 "best-scoring <b>available</b>
     * model"). Used by routing to avoid selecting a registered-but-not-pulled model. An empty set
     * means "availability unknown" (e.g. engine unreachable) and callers MUST treat it as
     * do-not-filter rather than nothing-available, so a transient outage never narrows routing.
     */
    default Set<String> availableModels() {
        return Set.of();
    }
}
