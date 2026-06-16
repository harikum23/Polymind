package com.polymind.knowledge;

import com.polymind.inference.ChatMessage;
import com.polymind.persistence.VectorChunk;
import com.polymind.persistence.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Active knowledge layer (ARCHITECTURE.md §5 flow): query -> embed -> vector search in pack ->
 * top-K -> budget-trim -> inject as a system message -> grounded generation. Only loaded when
 * {@code polymind.knowledge.enabled=true} (and Postgres+pgvector is configured).
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "true")
public class ActiveKnowledgeService implements KnowledgeService {

    private final KnowledgeIndexer indexer;
    private final KnowledgeRetriever retriever;
    private final ContextInjector injector;
    private final VectorStore store;

    public ActiveKnowledgeService(KnowledgeIndexer indexer, KnowledgeRetriever retriever,
                                  ContextInjector injector, VectorStore store) {
        this.indexer = indexer;
        this.retriever = retriever;
        this.injector = injector;
        this.store = store;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<ChatMessage> augment(List<ChatMessage> messages, String pack) {
        if (pack == null || pack.isBlank()) {
            return messages;
        }
        String query = latestUserContent(messages);
        if (query.isBlank()) {
            return messages;
        }
        List<VectorChunk> chunks = retriever.retrieve(pack, query);
        String context = injector.buildContextBlock(chunks);
        if (context.isEmpty()) {
            return messages;
        }
        List<ChatMessage> augmented = new ArrayList<>();
        augmented.add(ChatMessage.of("system", context));
        augmented.addAll(messages);
        return augmented;
    }

    @Override
    public int index(String pack, String source, String text) {
        return indexer.indexDocument(pack, source, text);
    }

    @Override
    public void reindex(String pack, List<Document> documents) {
        indexer.reindex(pack, documents);
    }

    @Override
    public long count(String pack) {
        return store.count(pack);
    }

    private String latestUserContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null) {
                return m.content();
            }
        }
        return "";
    }
}
