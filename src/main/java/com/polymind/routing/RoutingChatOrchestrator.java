package com.polymind.routing;

import com.polymind.admission.AdmissionControl;
import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatMessage;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.knowledge.KnowledgeService;
import com.polymind.observability.RoutingMetrics;
import com.polymind.resilience.ResilientInferenceGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Routing-aware orchestrator. Resolves the {@code model} control knob via {@link ModelRouter}, then
 * dispatches through {@link AdmissionControl} (concurrency + backpressure) and
 * {@link ResilientInferenceGateway} (circuit breaker / retry / bulkhead). Records routing metrics.
 */
@Component("routingChatOrchestrator")
public class RoutingChatOrchestrator implements ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatOrchestrator.class);

    private final ModelRouter router;
    private final ModelRegistry registry;
    private final ResilientInferenceGateway gateway;
    private final AdmissionControl admission;
    private final RoutingMetrics metrics;
    private final KnowledgeService knowledge;

    public RoutingChatOrchestrator(ModelRouter router, ModelRegistry registry,
                                   ResilientInferenceGateway gateway, AdmissionControl admission,
                                   RoutingMetrics metrics, KnowledgeService knowledge) {
        this.router = router;
        this.registry = registry;
        this.gateway = gateway;
        this.admission = admission;
        this.metrics = metrics;
        this.knowledge = knowledge;
    }

    @Override
    public String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        RouteDecision decision = decide(request);
        ChatRequest finalReq = applyKnowledge(request).withModel(decision.modelId());
        admission.runStreaming(AdmissionControl.Priority.NORMAL, () ->
                gateway.streamChat(decision.engine(), finalReq, onChunk));
        return decision.modelId();
    }

    @Override
    public ChatOutcome chat(ChatRequest request) {
        RouteDecision decision = decide(request);
        ChatRequest finalReq = applyKnowledge(request).withModel(decision.modelId());
        ChatResult result = admission.submit(AdmissionControl.Priority.NORMAL, () ->
                gateway.chat(decision.engine(), finalReq));
        return new ChatOutcome(decision.modelId(), result.content(), result.finishReason(), result.usage());
    }

    /** Step 5: if metadata.knowledge_pack is set and the layer is active, inject retrieved context. */
    private ChatRequest applyKnowledge(ChatRequest request) {
        if (!knowledge.isEnabled()) {
            return request;
        }
        Map<String, Object> metadata = request.extraOptions() == null ? Map.of() : request.extraOptions();
        Object pack = metadata.get("knowledge_pack");
        if (pack == null) {
            return request;
        }
        List<ChatMessage> augmented = knowledge.augment(request.messages(), String.valueOf(pack));
        return request.withMessages(augmented);
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
        EmbeddingRequest resolved = new EmbeddingRequest(model, request.input());
        return admission.submit(AdmissionControl.Priority.LOW, () -> gateway.embed(engine, resolved));
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
        metrics.recordRoute(decision.modelId(), decision.category().key(), decision.reason());
        log.info("model='{}' -> {} (engine={}, reason={})",
                request.model(), decision.modelId(), decision.engine(), decision.reason());
        return decision;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
