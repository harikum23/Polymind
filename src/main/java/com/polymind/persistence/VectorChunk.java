package com.polymind.persistence;

/**
 * A stored, embedded chunk. {@code score} is the similarity (1 - cosine distance) on retrieval.
 */
public record VectorChunk(long id, String pack, String source, String content, double score) {

    public static VectorChunk forStore(String pack, String source, String content) {
        return new VectorChunk(0, pack, source, content, 0);
    }
}
