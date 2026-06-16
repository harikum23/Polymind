package com.polymind.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatMessage;
import com.polymind.inference.ChatRequest;
import com.polymind.routing.ChatOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible {@code POST /v1/chat/completions}. Supports streaming (Server-Sent Events,
 * relayed token-by-token on a virtual thread) and non-streaming responses.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Chat", description = "OpenAI-compatible chat completions")
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);

    private final ChatOrchestrator orchestrator;
    private final ObjectMapper mapper;

    public ChatCompletionsController(ChatOrchestrator orchestrator, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @PostMapping(value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @Operation(summary = "Create a chat completion",
            description = "model = concrete id (force) | category alias (chat/code/math/reasoning) | 'auto'. "
                    + "Set stream=true for SSE deltas.")
    public Object create(@RequestBody com.polymind.web.dto.OpenAiChatRequest req) {
        ChatRequest engineReq = toEngineRequest(req);
        boolean stream = Boolean.TRUE.equals(req.stream());
        return stream ? streaming(engineReq) : nonStreaming(engineReq);
    }

    // ---- non-streaming ----

    private com.polymind.web.dto.OpenAiChatResponse nonStreaming(ChatRequest engineReq) {
        ChatOrchestrator.ChatOutcome outcome = orchestrator.chat(engineReq);
        var usage = new com.polymind.web.dto.OpenAiChatResponse.Usage(
                outcome.usage().promptTokens(),
                outcome.usage().completionTokens(),
                outcome.usage().totalTokens());
        var choice = new com.polymind.web.dto.OpenAiChatResponse.Choice(
                0,
                new com.polymind.web.dto.OpenAiChatResponse.Message("assistant", outcome.content()),
                null,
                outcome.finishReason());
        return new com.polymind.web.dto.OpenAiChatResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                System.currentTimeMillis() / 1000,
                outcome.resolvedModel(),
                List.of(choice),
                usage);
    }

    // ---- streaming (SSE) ----

    private SseEmitter streaming(ChatRequest engineReq) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; virtual thread holds the connection
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000;
        AtomicReference<String> modelRef = new AtomicReference<>(engineReq.model());

        Thread.startVirtualThread(() -> {
            try {
                boolean[] first = {true};
                String model = orchestrator.streamChat(engineReq, chunk -> {
                    try {
                        emitChunk(emitter, id, created, modelRef.get(), chunk, first);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                modelRef.set(model);
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE stream failed: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void emitChunk(SseEmitter emitter, String id, long created, String model,
                           ChatChunk chunk, boolean[] first) throws IOException {
        com.polymind.web.dto.OpenAiChatResponse.Delta delta;
        String finishReason = null;
        if (chunk.done()) {
            delta = new com.polymind.web.dto.OpenAiChatResponse.Delta(null, null);
            finishReason = chunk.finishReason();
        } else {
            String role = first[0] ? "assistant" : null;
            first[0] = false;
            delta = new com.polymind.web.dto.OpenAiChatResponse.Delta(role, chunk.contentDelta());
        }
        var choice = new com.polymind.web.dto.OpenAiChatResponse.Choice(0, null, delta, finishReason);
        var payload = new com.polymind.web.dto.OpenAiChatResponse(
                id, "chat.completion.chunk", created, model, List.of(choice), null);
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(payload)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    // ---- mapping ----

    private ChatRequest toEngineRequest(com.polymind.web.dto.OpenAiChatRequest req) {
        List<ChatMessage> messages = req.messages().stream()
                .map(m -> ChatMessage.of(m.role(), m.content()))
                .toList();
        return new ChatRequest(
                req.model(),
                messages,
                req.temperature(),
                req.topP(),
                req.maxTokens(),
                req.stream(),
                req.tools(),
                req.metadata());
    }
}
