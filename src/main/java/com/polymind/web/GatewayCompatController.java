package com.polymind.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.polymind.inference.ChatMessage;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.routing.ChatOrchestrator;
import com.polymind.tools.SearchResult;
import com.polymind.tools.WebSearchService;
import com.polymind.web.dto.OpenAiChatRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemma-gateway compatibility layer. Exposes the legacy gateway contract that the Trade Engine
 * client ({@code TradeEngine/src/gateway/client.py}) already speaks, so that consumer can switch
 * to Polymind by changing only its base URL + API key — no client code change.
 *
 * <ul>
 *   <li>{@code POST /v1/generate} → {@link ChatOrchestrator#chat} (returns {@code content}).</li>
 *   <li>{@code POST /v1/embed}    → {@link ChatOrchestrator#embed} (returns {@code embeddings}).</li>
 *   <li>{@code POST /v1/agent}    → web-search-augmented single-step answer
 *       (returns {@code content}, {@code steps}, {@code sources}).</li>
 * </ul>
 *
 * Native OpenAI endpoints ({@code /v1/chat/completions}, {@code /v1/embeddings},
 * {@code /v1/tools/web_search}) remain the preferred surface; this layer is the migration bridge.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Gateway-compat", description = "Legacy gemma-gateway contract (migration bridge)")
public class GatewayCompatController {

    private static final Logger log = LoggerFactory.getLogger(GatewayCompatController.class);

    private final ChatOrchestrator orchestrator;
    private final WebSearchService webSearch;
    private final String defaultModel;
    private final String defaultEmbedModel;
    private final int agentMaxTokens;
    private final String defaultKnowledgePack;

    public GatewayCompatController(
            ChatOrchestrator orchestrator,
            WebSearchService webSearch,
            @Value("${polymind.gateway-compat.default-model:qwen2.5-7b}") String defaultModel,
            @Value("${polymind.gateway-compat.default-embed-model:nomic-embed}") String defaultEmbedModel,
            @Value("${polymind.gateway-compat.agent-max-tokens:512}") int agentMaxTokens,
            @Value("${polymind.gateway-compat.knowledge-pack:}") String defaultKnowledgePack) {
        this.orchestrator = orchestrator;
        this.webSearch = webSearch;
        this.defaultModel = defaultModel;
        this.defaultEmbedModel = defaultEmbedModel;
        this.agentMaxTokens = agentMaxTokens;
        this.defaultKnowledgePack = defaultKnowledgePack;
    }

    /**
     * Merge per-request {@code metadata} with the configured default knowledge pack. The legacy
     * gemma-gateway wire shape has no metadata field, so this is how consumers like TradeEngine —
     * which speak only {@code /v1/generate} and {@code /v1/agent} — pick up knowledge augmentation.
     * When {@code polymind.gateway-compat.knowledge-pack} is set, every compat call gets that pack;
     * the knowledge layer's relevance gate ({@code polymind.knowledge.min-score}) ensures only
     * pertinent calls actually receive injected context. Per-request metadata overrides the default.
     */
    private Map<String, Object> buildExtraOptions(Map<String, Object> metadata) {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (defaultKnowledgePack != null && !defaultKnowledgePack.isBlank()) {
            opts.put("knowledge_pack", defaultKnowledgePack);
        }
        if (metadata != null) {
            opts.putAll(metadata); // caller-supplied metadata wins over the server default
        }
        return opts.isEmpty() ? null : opts;
    }

    // ---- /v1/generate -----------------------------------------------------

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Legacy generate (gemma-gateway compat)",
            description = "Non-streaming chat. Returns the answer under both 'content' and 'text'. "
                    + "Optional 'metadata' (knowledge_pack, web_search, task, force) is forwarded to "
                    + "the orchestrator; when polymind.gateway-compat.knowledge-pack is configured, "
                    + "that pack is applied by default and retrieved context is injected whenever it "
                    + "clears the relevance gate (polymind.knowledge.min-score).")
    public GenerateResponse generate(@RequestBody GenerateRequest req) {
        String model = blankToNull(req.model()) != null ? req.model() : defaultModel;
        ChatRequest engineReq = new ChatRequest(
                model,
                toEngineMessages(req.messages()),
                req.temperature(),
                req.topP(),
                req.maxTokens(),
                Boolean.FALSE,
                null,
                buildExtraOptions(req.metadata()));
        ChatOrchestrator.ChatOutcome out = orchestrator.chat(engineReq);
        int in = out.usage() == null ? 0 : out.usage().promptTokens();
        int outTok = out.usage() == null ? 0 : out.usage().completionTokens();
        return new GenerateResponse(out.content(), out.content(), out.resolvedModel(),
                in, outTok, out.finishReason());
    }

    // ---- /v1/embed --------------------------------------------------------

    @PostMapping(value = "/embed", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Legacy embed (gemma-gateway compat)")
    public EmbedResponse embed(@RequestBody EmbedRequest req) {
        String model = blankToNull(req.model()) != null ? req.model() : defaultEmbedModel;
        List<String> texts = req.texts() == null ? List.of() : req.texts();
        EmbeddingResult result = orchestrator.embed(new EmbeddingRequest(model, texts));
        int in = result.usage() == null ? 0 : result.usage().promptTokens();
        return new EmbedResponse(result.vectors(), result.model(), in);
    }

    // ---- /v1/agent --------------------------------------------------------

    @PostMapping(value = "/agent", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Legacy agent (gemma-gateway compat)",
            description = "Single-step, web-search-augmented answer. Returns content, steps, sources. "
                    + "Optional 'metadata' behaves as on /v1/generate (default knowledge pack + "
                    + "relevance-gated context injection), stacking digest context on top of the "
                    + "live web-search results.")
    public AgentResponse agent(@RequestBody AgentRequest req) {
        String query = lastUserMessage(req.messages());
        Agent opts = req.agent() == null ? new Agent(null, null, null, null) : req.agent();

        // Run a web-search pass unless explicitly given a tool list that omits it.
        boolean useSearch = opts.builtinTools() == null
                || opts.builtinTools().isEmpty()
                || opts.builtinTools().contains("web_search");

        String contextBlock = "";
        List<SourceDto> sources = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (useSearch && !query.isBlank()) {
            try {
                SearchResult sr = webSearch.search(query, null);
                contextBlock = sr.text() == null ? "" : sr.text();
                for (SearchResult.Source s : sr.sources()) {
                    sources.add(new SourceDto(s.title(), s.url(), s.snippet()));
                }
                steps.add(Map.of("type", "web_search", "query", query, "results", sources.size()));
            } catch (RuntimeException e) {
                // Fail open: answer without fresh context rather than 500, matching the
                // client's fail-soft expectation (it treats null/empty as "use fallback").
                log.warn("agent web_search failed, answering without context: {}", e.getMessage());
                steps.add(Map.of("type", "web_search", "query", query, "error", String.valueOf(e.getMessage())));
            }
        }

        List<ChatMessage> messages = new ArrayList<>();
        String system = blankToNull(opts.system()) != null ? opts.system()
                : "You are a concise research assistant. Use the provided web results when present; "
                + "cite concrete facts and do not invent sources.";
        messages.add(ChatMessage.of("system", system));
        String userContent = contextBlock.isBlank()
                ? query
                : query + "\n\nWeb search results:\n" + contextBlock
                + "\n\nAnswer using the results above.";
        messages.add(ChatMessage.of("user", userContent));

        ChatRequest engineReq = new ChatRequest(
                defaultModel, messages, 0.2, null, agentMaxTokens, Boolean.FALSE, null,
                buildExtraOptions(req.metadata()));
        ChatOrchestrator.ChatOutcome out = orchestrator.chat(engineReq);
        int in = out.usage() == null ? 0 : out.usage().promptTokens();
        int outTok = out.usage() == null ? 0 : out.usage().completionTokens();
        return new AgentResponse(out.content(), out.content(), steps, sources, in, outTok);
    }

    // ---- helpers ----------------------------------------------------------

    private static List<ChatMessage> toEngineMessages(List<OpenAiChatRequest.Message> msgs) {
        List<ChatMessage> out = new ArrayList<>();
        if (msgs != null) {
            for (OpenAiChatRequest.Message m : msgs) {
                out.add(ChatMessage.of(m.role(), m.content()));
            }
        }
        return out;
    }

    private static String lastUserMessage(List<OpenAiChatRequest.Message> msgs) {
        if (msgs == null) {
            return "";
        }
        String last = "";
        for (OpenAiChatRequest.Message m : msgs) {
            if (m.role() == null || "user".equalsIgnoreCase(m.role())) {
                last = m.content() == null ? "" : m.content();
            }
        }
        return last;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ---- DTOs (legacy gemma-gateway wire shapes) --------------------------

    @JsonIgnoreProperties(ignoreUnknown = true) // tolerate 'tools', 'format', etc.
    public record GenerateRequest(
            List<OpenAiChatRequest.Message> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Boolean stream,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            String model,
            // Optional passthrough (knowledge_pack, web_search, task, force); merged with the
            // server-side default pack. Absent in the original gemma-gateway shape — additive.
            @Schema(description = "Optional Polymind extensions: knowledge_pack (RAG pack name; "
                    + "overrides the configured default), web_search (true = inject live results), "
                    + "task (chat|code|math|reasoning hint), force (forbid fallback).",
                    example = "{\"knowledge_pack\": \"trade-engine\"}")
            Map<String, Object> metadata) {}

    public record GenerateResponse(
            String content,
            String text,
            String model,
            @JsonProperty("tokens_in") int tokensIn,
            @JsonProperty("tokens_out") int tokensOut,
            @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedRequest(List<String> texts, String model) {}

    public record EmbedResponse(
            List<float[]> embeddings,
            String model,
            @JsonProperty("tokens_in") int tokensIn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentRequest(List<OpenAiChatRequest.Message> messages, Agent agent,
                               @Schema(description = "Optional Polymind extensions — same semantics "
                                       + "as on /v1/generate.",
                                       example = "{\"knowledge_pack\": \"trade-engine\"}")
                               Map<String, Object> metadata) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Agent(
            String system,
            @JsonProperty("builtin_tools") List<String> builtinTools,
            @JsonProperty("max_steps") Integer maxSteps,
            @JsonProperty("total_budget_ms") Long totalBudgetMs) {}

    public record AgentResponse(
            String content,
            String text,
            List<Map<String, Object>> steps,
            List<SourceDto> sources,
            @JsonProperty("tokens_in") int tokensIn,
            @JsonProperty("tokens_out") int tokensOut) {}

    public record SourceDto(String title, String url, String snippet) {}
}
