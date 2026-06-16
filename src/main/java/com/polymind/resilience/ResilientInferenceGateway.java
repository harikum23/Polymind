package com.polymind.resilience;

import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.inference.Engine;
import com.polymind.inference.EngineRegistry;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single entry point through which all inference calls flow, wrapped with per-engine circuit
 * breaker, retry and bulkhead. The smart switch (routing) dispatches through this gateway instead
 * of touching {@link EngineRegistry} directly.
 *
 * <p>Non-streaming {@code chat}/{@code embed} get CB + retry + bulkhead. Streaming acquires a
 * bulkhead permit and circuit-breaker guard at initiation; the long-lived token relay itself is not
 * retried (retrying a partially-streamed completion would duplicate tokens).
 */
@Service
public class ResilientInferenceGateway {

    private final EngineRegistry engines;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;

    public ResilientInferenceGateway(EngineRegistry engines,
                                     CircuitBreakerRegistry circuitBreakers,
                                     RetryRegistry retries,
                                     BulkheadRegistry bulkheads) {
        this.engines = engines;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
    }

    public ChatResult chat(String engineName, ChatRequest request) {
        Engine engine = engines.require(engineName);
        return protectedCall(engineName, () -> engine.chat(request));
    }

    public EmbeddingResult embed(String engineName, EmbeddingRequest request) {
        Engine engine = engines.require(engineName);
        return protectedCall(engineName, () -> engine.embed(request));
    }

    public void streamChat(String engineName, ChatRequest request, Consumer<ChatChunk> onChunk) {
        Engine engine = engines.require(engineName);
        CircuitBreaker cb = circuitBreakers.circuitBreaker(engineName);
        Bulkhead bulkhead = bulkheads.bulkhead(engineName);
        // Guard the initiation with CB + bulkhead; relay tokens directly (no retry on a live stream).
        Runnable guarded = Decorators.ofRunnable(() -> engine.streamChat(request, onChunk))
                .withCircuitBreaker(cb)
                .withBulkhead(bulkhead)
                .decorate();
        guarded.run();
    }

    private <T> T protectedCall(String engineName, Supplier<T> call) {
        CircuitBreaker cb = circuitBreakers.circuitBreaker(engineName);
        Retry retry = retries.retry(engineName);
        Bulkhead bulkhead = bulkheads.bulkhead(engineName);
        return Decorators.ofSupplier(call)
                .withCircuitBreaker(cb)
                .withBulkhead(bulkhead)
                .withRetry(retry)
                .decorate()
                .get();
    }
}
