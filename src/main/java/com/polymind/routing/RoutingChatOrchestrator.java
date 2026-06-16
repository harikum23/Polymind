package com.polymind.routing;

import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.inference.EngineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Routing-aware orchestrator (step 3). Resolves the {@code model} control knob through
 * {@link ModelRouter}, then dispatches to the chosen engine via {@link EngineRegistry}. Registered
 * as {@code routingChatOrchestrator}, which deactivates the step-2 passthrough bean.
 */
@Component("routingChatOrchestrator")
public class RoutingChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatOrchestrator.class);

    private final ModelRouter router;
    private final ModelRegistry registry;
    private final EngineRegistry engines;

    public RoutingChatOrchestrator(ModelRouter router, ModelRegistry registry, EngineRegistry engines) {
        this.router = router;
        this.registry = registry;
        this.engines = engines;
    }

    @Override
    public String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        RouteDecision decision = decide(request);
        engines.require(decision.engine()).streamChat(request.withModel(decision.modelId()), onChunk);
        return decision.modelId();
    }

    @Override
    public ChatOutcome chat(ChatRequest request) {
        RouteDecision decision = decide(request);
        ChatResult result = engines.require(decision.engine())
                .chat(request.withModel(decision.modelId()));
        return new ChatOutcome(decision.modelId(), result.content(), result.finishReason(), result.usage());
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        String model = request.model();
        if (model == null || model.isBlank()) {
            model = registry.defaultEmbedModel()
                    .map(ModelSpec::id)
                    .orElseThrow(() -> new IllegalStateException("No embedding model registered"));
        }
        String engine = registry.find(model).map(ModelSpec::engine).orElse("ollama");
        return engines.require(engine).embed(new EmbeddingRequest(model, request.input()));
    }

    @SuppressWarnings("unchecked")
    private RouteDecision decide(ChatRequest request) {
        Map<String, Object> metadata = request.extraOptions() == null ? Map.of() : request.extraOptions();
        String taskHint = asString(metadata.get("task"));
        boolean force = Boolean.TRUE.equals(metadata.get("force"))
                || "true".equalsIgnoreCase(asString(metadata.get("force")));
        boolean needsTools = request.tools() != null && !request.tools().isEmpty();
        List<String> userMessages = request.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .map(m -> m.content() == null ? "" : m.content())
                .toList();
        RouteDecision decision = router.route(
                new RouteQuery(request.model(), taskHint, userMessages, needsTools, force));
        log.info("model='{}' -> {} (engine={}, reason={})",
                request.model(), decision.modelId(), decision.engine(), decision.reason());
        return decision;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
