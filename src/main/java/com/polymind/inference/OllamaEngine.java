package com.polymind.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Ollama adapter. Talks to Ollama's native {@code /api/chat} (NDJSON streaming) and
 * {@code /api/embeddings} over JDK 21 {@link HttpClient} (HTTP/2). Maps Ollama responses to
 * OpenAI-shaped deltas with correct {@code finish_reason} / usage (ARCHITECTURE.md step 2).
 */
@Component
@EnableConfigurationProperties(OllamaProperties.class)
public class OllamaEngine implements Engine {

    private static final Logger log = LoggerFactory.getLogger(OllamaEngine.class);

    private final OllamaProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public OllamaEngine(OllamaProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                // Virtual-thread executor: each streaming relay runs on a cheap carrier-free thread.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public void streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        ObjectNode body = buildChatBody(request, true);
        HttpRequest httpReq = post("/api/chat", body);
        try {
            HttpResponse<java.io.InputStream> resp =
                    http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new EngineException("Ollama chat failed: HTTP " + resp.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String finishReason = "stop";
                int promptTokens = 0;
                int completionTokens = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    if (node.has("message")) {
                        String content = node.path("message").path("content").asText("");
                        if (!content.isEmpty()) {
                            onChunk.accept(ChatChunk.delta(content));
                        }
                    }
                    if (node.path("done").asBoolean(false)) {
                        finishReason = mapFinishReason(node.path("done_reason").asText("stop"));
                        promptTokens = node.path("prompt_eval_count").asInt(0);
                        completionTokens = node.path("eval_count").asInt(0);
                        onChunk.accept(ChatChunk.terminal(finishReason,
                                new ChatChunk.Usage(promptTokens, completionTokens)));
                        return;
                    }
                }
                // Stream ended without an explicit done marker.
                onChunk.accept(ChatChunk.terminal(finishReason,
                        new ChatChunk.Usage(promptTokens, completionTokens)));
            }
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("Ollama chat stream error: " + e.getMessage(), e);
        }
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        ChatChunk[] terminal = new ChatChunk[1];
        streamChat(request, chunk -> {
            if (chunk.contentDelta() != null) {
                sb.append(chunk.contentDelta());
            }
            if (chunk.done()) {
                terminal[0] = chunk;
            }
        });
        ChatChunk t = terminal[0];
        return new ChatResult(sb.toString(),
                t != null ? t.finishReason() : "stop",
                t != null ? t.usage() : new ChatChunk.Usage(0, 0));
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        List<float[]> vectors = new ArrayList<>();
        int promptTokens = 0;
        try {
            for (String text : request.input()) {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", request.model());
                body.put("prompt", text);
                HttpResponse<String> resp = http.send(post("/api/embeddings", body),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    throw new EngineException("Ollama embeddings failed: HTTP " + resp.statusCode());
                }
                JsonNode node = mapper.readTree(resp.body());
                ArrayNode arr = (ArrayNode) node.path("embedding");
                float[] vec = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    vec[i] = (float) arr.get(i).asDouble();
                }
                vectors.add(vec);
                promptTokens += node.path("prompt_eval_count").asInt(0);
            }
            return new EmbeddingResult(request.model(), vectors, new ChatChunk.Usage(promptTokens, 0));
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("Ollama embeddings error: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/tags"))
                    .timeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .GET()
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (Exception e) {
            log.debug("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ---- helpers ----

    private ObjectNode buildChatBody(ChatRequest request, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role());
            msg.put("content", m.content() == null ? "" : m.content());
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.set("tools", mapper.valueToTree(request.tools()));
        }
        ObjectNode options = body.putObject("options");
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            options.put("top_p", request.topP());
        }
        if (request.maxTokens() != null) {
            options.put("num_predict", request.maxTokens());
        }
        return body;
    }

    private HttpRequest post(String path, ObjectNode body) {
        try {
            return HttpRequest.newBuilder(URI.create(props.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new EngineException("Failed to build Ollama request: " + e.getMessage(), e);
        }
    }

    private String mapFinishReason(String ollamaReason) {
        return switch (ollamaReason) {
            case "length" -> "length";
            case "stop", "", "load" -> "stop";
            default -> ollamaReason;
        };
    }
}
