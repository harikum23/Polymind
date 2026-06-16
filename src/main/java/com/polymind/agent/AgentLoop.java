package com.polymind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatMessage;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.resilience.ResilientInferenceGateway;
import com.polymind.tools.SearchResult;
import com.polymind.tools.WebSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct / tool-use loop (ARCHITECTURE.md §6, access pattern #2). Instructs the model to either emit
 * a JSON action invoking the {@code web_search} tool or to produce a final answer, executes the
 * tool, feeds the observation back, and repeats up to a step budget — recording a full trace.
 *
 * <p>This JSON-action protocol is model-agnostic (works without native tool-call support). Native
 * OpenAI/Ollama tool-call parsing is a documented refinement (docs/future-pending.md).
 */
@Service
@EnableConfigurationProperties(AgentProperties.class)
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private static final String SYSTEM_PROMPT = """
            You are a research agent with access to one tool: web_search.
            On each turn respond with EXACTLY ONE JSON object and nothing else:
            - To search the web: {"action":"web_search","query":"<search terms>"}
            - To answer the user: {"action":"final","answer":"<your grounded answer>"}
            Prefer searching when the question needs live or external information. Keep queries focused.
            """;

    private final ResilientInferenceGateway gateway;
    private final WebSearchService webSearch;
    private final AgentProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentLoop(ResilientInferenceGateway gateway, WebSearchService webSearch, AgentProperties props) {
        this.gateway = gateway;
        this.webSearch = webSearch;
        this.props = props;
    }

    public AgentResult run(String engine, String model, List<ChatMessage> initialMessages, String keyId) {
        AgentTrace trace = new AgentTrace();
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.of("system", SYSTEM_PROMPT));
        conversation.addAll(initialMessages);

        int promptTokens = 0;
        int completionTokens = 0;

        for (int step = 1; step <= props.getMaxSteps(); step++) {
            ChatResult result = gateway.chat(engine, plainRequest(model, conversation));
            promptTokens += result.usage().promptTokens();
            completionTokens += result.usage().completionTokens();
            String raw = result.content() == null ? "" : result.content().strip();
            trace.add("model", raw);

            Action action = parse(raw);
            if (action == null || action.isFinal()) {
                String answer = action == null ? raw : action.answer();
                trace.add("final", answer);
                return new AgentResult(answer, model, trace,
                        new ChatChunk.Usage(promptTokens, completionTokens));
            }

            trace.add("tool_call", "web_search: " + action.query());
            String observation;
            try {
                SearchResult search = webSearch.searchForKey(keyId, action.query(), null);
                observation = search.text().isBlank() ? "(no results)" : search.text();
            } catch (Exception e) {
                observation = "Search failed: " + e.getMessage();
            }
            trace.add("observation", observation);
            conversation.add(ChatMessage.of("assistant", raw));
            conversation.add(ChatMessage.of("user", "Observation:\n" + observation));
        }

        // Budget exhausted: force a final answer.
        ChatResult forced = gateway.chat(engine, plainRequest(model, withForceFinal(conversation)));
        promptTokens += forced.usage().promptTokens();
        completionTokens += forced.usage().completionTokens();
        trace.add("final", "step-budget-exhausted");
        return new AgentResult(forced.content(), model, trace,
                new ChatChunk.Usage(promptTokens, completionTokens));
    }

    private List<ChatMessage> withForceFinal(List<ChatMessage> conversation) {
        List<ChatMessage> copy = new ArrayList<>(conversation);
        copy.add(ChatMessage.of("user", "Provide your final answer now based on what you have."));
        return copy;
    }

    private ChatRequest plainRequest(String model, List<ChatMessage> messages) {
        return new ChatRequest(model, messages, 0.2, null, null, false, null, null);
    }

    private Action parse(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode node = mapper.readTree(raw.substring(start, end + 1));
            String action = node.path("action").asText("");
            if ("final".equals(action)) {
                return Action.finalAnswer(node.path("answer").asText(""));
            }
            if ("web_search".equals(action)) {
                return Action.search(node.path("query").asText(""));
            }
            return null;
        } catch (Exception e) {
            log.debug("Agent could not parse action JSON: {}", e.getMessage());
            return null;
        }
    }

    private record Action(String type, String query, String answer) {
        static Action search(String query) {
            return new Action("web_search", query, null);
        }

        static Action finalAnswer(String answer) {
            return new Action("final", null, answer);
        }

        boolean isFinal() {
            return "final".equals(type);
        }
    }
}
