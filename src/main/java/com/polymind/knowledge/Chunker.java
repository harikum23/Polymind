package com.polymind.knowledge;

import java.util.ArrayList;
import java.util.List;

/** Fixed-size sliding-window character chunker with overlap. Pluggable for smarter splitting later. */
final class Chunker {

    private Chunker() {
    }

    static List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String normalized = text.strip();
        int step = Math.max(1, size - overlap);
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(normalized.length(), start + size);
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
