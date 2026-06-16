package com.polymind.knowledge;

import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.inference.EngineRegistry;
import com.polymind.persistence.VectorChunk;
import com.polymind.persistence.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Offline/background indexer (ARCHITECTURE.md §5): chunk -> embed (via the Ollama embed model) ->
 * store vectors in the selected pack. Runs on a virtual thread via {@code @Async} so indexing never
 * blocks request handling.
 */
@Service
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private final VectorStore store;
    private final EngineRegistry engines;
    private final KnowledgeProperties props;

    public KnowledgeIndexer(VectorStore store, EngineRegistry engines, KnowledgeProperties props) {
        this.store = store;
        this.engines = engines;
        this.props = props;
    }

    /** Synchronously index one document into a pack. Returns the number of chunks stored. */
    public int indexDocument(String pack, String source, String text) {
        List<String> chunks = Chunker.chunk(text, props.getChunkChars(), props.getChunkOverlapChars());
        if (chunks.isEmpty()) {
            return 0;
        }
        EmbeddingResult embeddings = engines.require("ollama")
                .embed(new EmbeddingRequest(props.getEmbedModel(), chunks));
        for (int i = 0; i < chunks.size(); i++) {
            store.store(VectorChunk.forStore(pack, source, chunks.get(i)), embeddings.vectors().get(i));
        }
        log.info("Indexed {} chunks into pack '{}' from '{}'", chunks.size(), pack, source);
        return chunks.size();
    }

    @Async
    public void indexDocumentAsync(String pack, String source, String text) {
        indexDocument(pack, source, text);
    }

    public void reindex(String pack, List<KnowledgeService.Document> documents) {
        store.clearPack(pack);
        documents.forEach(d -> indexDocument(pack, d.source(), d.text()));
    }
}
