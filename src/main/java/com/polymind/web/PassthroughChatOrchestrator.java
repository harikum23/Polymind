package com.polymind.web;

import com.polymind.inference.ChatChunk;
import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.inference.Engine;
import com.polymind.inference.EngineRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Step-2 orchestrator: passes the caller's {@code model} straight to the default engine (Ollama),
 * treating it as a concrete model id. Routing/knowledge/agent layers replace this in later steps
 * (this bean backs off automatically via {@link ConditionalOnMissingBean} once a smarter
 * orchestrator is registered).
 */
@Component
@ConditionalOnMissingBean(name = "routingChatOrchestrator")
public class PassthroughChatOrchestrator implements ChatOrchestrator {

    private static final String DEFAULT_ENGINE = "ollama";
    private static final String DEFAULT_CHAT_MODEL = "qwen2.5-7b";
    private static final String DEFAULT_EMBED_MODEL = "nomic-embed";

    private final EngineRegistry engines;

    public PassthroughChatOrchestrator(EngineRegistry engines) {
        this.engines = engines;
    }

    @Override
    public String streamChat(ChatRequest request, Consumer<ChatChunk> onChunk) {
        String model = resolveChatModel(request.model());
        engines.require(DEFAULT_ENGINE).streamChat(request.withModel(model), onChunk);
        return model;
    }

    @Override
    public ChatOutcome chat(ChatRequest request) {
        String model = resolveChatModel(request.model());
        Engine engine = engines.require(DEFAULT_ENGINE);
        ChatResult result = engine.chat(request.withModel(model));
        return new ChatOutcome(model, result.content(), result.finishReason(), result.usage());
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        String model = (request.model() == null || request.model().isBlank())
                ? DEFAULT_EMBED_MODEL : request.model();
        return engines.require(DEFAULT_ENGINE)
                .embed(new EmbeddingRequest(model, request.input()));
    }

    private String resolveChatModel(String model) {
        if (model == null || model.isBlank() || model.equals("auto")) {
            return DEFAULT_CHAT_MODEL;
        }
        return model;
    }
}
