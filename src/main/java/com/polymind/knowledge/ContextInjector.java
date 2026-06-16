package com.polymind.knowledge;

import com.polymind.persistence.VectorChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Injector (ARCHITECTURE.md §5): build a token-budgeted context block from retrieved chunks,
 * trimming by relevance order so room is reserved for the answer (~4 chars/token heuristic).
 */
@Component
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "true")
public class ContextInjector {

    private final KnowledgeProperties props;

    public ContextInjector(KnowledgeProperties props) {
        this.props = props;
    }

    /** Returns a system-prompt-ready context block, or empty string if nothing fits. */
    public String buildContextBlock(List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        int budgetChars = props.getContextTokenBudget() * 4;
        StringBuilder sb = new StringBuilder("Use the following retrieved context to answer.\n\n");
        int used = sb.length();
        int n = 1;
        for (VectorChunk chunk : chunks) {
            String entry = "[" + n + "] (" + safeSource(chunk.source()) + ")\n" + chunk.content() + "\n\n";
            if (used + entry.length() > budgetChars) {
                break;
            }
            sb.append(entry);
            used += entry.length();
            n++;
        }
        return n == 1 ? "" : sb.toString().strip();
    }

    private String safeSource(String source) {
        return source == null ? "unknown" : source;
    }
}
