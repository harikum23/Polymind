package com.polymind.knowledge;

import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EngineRegistry;
import com.polymind.persistence.VectorChunk;
import com.polymind.persistence.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/** Retriever (ARCHITECTURE.md §5): embed the query, fetch top-K relevant chunks from a pack. */
@Service
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeRetriever {

    private final VectorStore store;
    private final EngineRegistry engines;
    private final KnowledgeProperties props;

    public KnowledgeRetriever(VectorStore store, EngineRegistry engines, KnowledgeProperties props) {
        this.store = store;
        this.engines = engines;
        this.props = props;
    }

    public List<VectorChunk> retrieve(String pack, String query) {
        float[] queryEmbedding = engines.require("ollama")
                .embed(new EmbeddingRequest(props.getEmbedModel(), List.of(query)))
                .vectors()
                .get(0);
        return store.search(pack, queryEmbedding, props.getTopK());
    }
}
