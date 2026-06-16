package com.polymind.knowledge;

import com.polymind.inference.ChatMessage;

import java.util.List;

/**
 * Published knowledge-layer API. Activated per request via {@code metadata.knowledge_pack}. Fully
 * optional: when the layer is disabled, {@link NoOpKnowledgeService} returns messages unchanged so
 * plain calls pay nothing (ARCHITECTURE.md §5 / §13).
 */
public interface KnowledgeService {

    boolean isEnabled();

    /**
     * If {@code pack} is set and the layer is active, retrieve top-K context for the latest user
     * message and prepend it as a system message. Otherwise returns {@code messages} unchanged.
     */
    List<ChatMessage> augment(List<ChatMessage> messages, String pack);

    /** Index one document into a pack. Returns chunks stored (0 if layer disabled). */
    int index(String pack, String source, String text);

    /** Re-index a pack from scratch. */
    void reindex(String pack, List<Document> documents);

    long count(String pack);

    record Document(String source, String text) {}
}
