package com.polymind.knowledge;

import com.polymind.inference.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Active when the knowledge layer is disabled (the default). Passes messages straight through, so
 * plain generation never pays for retrieval.
 */
@Service
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class NoOpKnowledgeService implements KnowledgeService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<ChatMessage> augment(List<ChatMessage> messages, String pack) {
        return messages;
    }

    @Override
    public int index(String pack, String source, String text) {
        return 0;
    }

    @Override
    public void reindex(String pack, List<Document> documents) {
        // no-op
    }

    @Override
    public long count(String pack) {
        return 0;
    }
}
