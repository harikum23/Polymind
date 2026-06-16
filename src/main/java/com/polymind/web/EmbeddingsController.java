package com.polymind.web;

import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.web.dto.OpenAiEmbeddingRequest;
import com.polymind.web.dto.OpenAiEmbeddingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** OpenAI-compatible {@code POST /v1/embeddings}. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Embeddings", description = "OpenAI-compatible embeddings")
public class EmbeddingsController {

    private final ChatOrchestrator orchestrator;

    public EmbeddingsController(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create embeddings")
    public OpenAiEmbeddingResponse create(@RequestBody OpenAiEmbeddingRequest req) {
        EmbeddingResult result = orchestrator.embed(new EmbeddingRequest(req.model(), req.inputs()));
        List<OpenAiEmbeddingResponse.Data> data = new ArrayList<>();
        List<float[]> vectors = result.vectors();
        for (int i = 0; i < vectors.size(); i++) {
            data.add(new OpenAiEmbeddingResponse.Data("embedding", i, vectors.get(i)));
        }
        var usage = new OpenAiEmbeddingResponse.Usage(
                result.usage().promptTokens(), result.usage().totalTokens());
        return new OpenAiEmbeddingResponse("list", data, result.model(), usage);
    }
}
