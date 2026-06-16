package com.polymind.routing;

import com.polymind.admission.AdmissionControl;
import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatMessage;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.agent.AgentLoop;
import com.polymind.agent.AgentResult;
import com.polymind.knowledge.KnowledgeService;
import com.polymind.observability.RoutingMetrics;
import com.polymind.resilience.ResilientInferenceGateway;
import com.polymind.tools.SearchResult;
import com.polymind.tools.WebSearchService;
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
    private final WebSearchService webSearch;
    private final AgentLoop agentLoop;

    public RoutingChatOrchestrator(ModelRouter router, ModelRegistry registry,
                                   ResilientInferenceGateway gateway, AdmissionControl admission,
                                   RoutingMetrics metrics, KnowledgeService knowledge,
                                   WebSearchService webSearch, AgentLoop agentLoop) {
        this.router = router;
        this.registry = registry;
        this.gateway = gateway;
        this.admission = admission;
        this.metrics = metrics;
        this.knowledge = knowledge;
        this.webSearch = webSearch;
        this.agentLoop = agentLoop;
    }

    @Override
    public String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        RouteDecision decision = decide(request);
        if (wantsAgent(request)) {
            // Agent loop is inherently multi-step; emit its final answer as a single delta + terminal.
            AgentResult agent = admission.submit(AdmissionControl.Priority.NORMAL, () ->
                    agentLoop.run(decision.engine(), decision.modelId(), request.messages(), keyId()));
            onChunk.accept(ChatChunk.delta(agent.content()));
            onChunk.accept(ChatChunk.terminal("stop", agent.usage()));
            return decision.modelId();
        }
        ChatRequest finalReq = augment(request).withModel(decision.modelId());
        admission.runStreaming(AdmissionControl.Priority.NORMAL, () ->
                gateway.streamChat(decision.engine(), finalReq, onChunk));
        return decision.modelId();
    }

    @Override
    public ChatOutcome chat(ChatRequest request) {
        RouteDecision decision = decide(request);
        if (wantsAgent(request)) {
            AgentResult agent = admission.submit(AdmissionControl.Priority.NORMAL, () ->
                    agentLoop.run(decision.engine(), decision.modelId(), request.messages(), keyId()));
            return new ChatOutcome(decision.modelId(), agent.content(), "stop", agent.usage());
        }
        ChatRequest finalReq = augment(request).withModel(decision.modelId());
        ChatResult result = admission.submit(AdmissionControl.Priority.NORMAL, () ->
                gateway.chat(decision.engine(), finalReq));
        return new ChatOutcome(decision.modelId(), result.content(), result.finishReason(), result.usage());
    }

    /** Apply optional augmentations in order: knowledge pack, then per-request web search (§8.2 #1). */
    private ChatRequest augment(ChatRequest request) {
        return applyWebSearch(applyKnowledge(request));
    }

    private boolean wantsAgent(ChatRequest request) {
        Map<String, Object> m = request.extraOptions() == null ? Map.of() : request.extraOptions();
        return isTrue(m.get("agent")) || (request.tools() != null && !request.tools().isEmpty());
    }

    /** §8.2 access pattern #1: metadata.web_search:true -> search latest user msg, inject results. */
    private ChatRequest applyWebSearch(ChatRequest request) {
        Map<String, Object> m = request.extraOptions() == null ? Map.of() : request.extraOptions();
        if (!isTrue(m.get("web_search"))) {
            return request;
        }
        String query = latestUser(request);
        if (query.isBlank()) {
            return request;
        }
        try {
            SearchResult search = webSearch.searchForKey(keyId(), query, null);
            if (search.text().isBlank()) {
                return request;
            }
            List<ChatMessage> augmented = new java.util.ArrayList<>();
            augmented.add(ChatMessage.of("system",
                    "Use the following live web search results to answer.\n\n" + search.text()));
            augmented.addAll(request.messages());
            return request.withMessages(augmented);
        } catch (Exception e) {
            log.warn("web_search injection failed, proceeding without: {}", e.getMessage());
            return request;
        }
    }

    private String latestUser(ChatRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage msg = request.messages().get(i);
            if ("user".equalsIgnoreCase(msg.role()) && msg.content() != null) {
                return msg.content();
            }
        }
        return "";
    }

    private boolean isTrue(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private String keyId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.polymind.tenancy.ApiKey key) {
            return key.id();
        }
        return "anonymous";
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
