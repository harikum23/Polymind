package com.polymind.persistence;

import java.util.List;

/**
 * Published port for the pgvector-backed knowledge index. The knowledge module's indexer stores
 * embedded chunks; the retriever queries top-K by similarity. Postgres+pgvector only; activated
 * with the knowledge layer (otherwise no bean is present and plain calls pay nothing).
 */
public interface VectorStore {

    /** Number of stored vectors (per pack). */
    long count(String pack);

    /** Store an embedded chunk. */
    void store(VectorChunk chunk, float[] embedding);

    /** Delete all chunks for a pack (re-index). */
    void clearPack(String pack);

    /** Top-K most similar chunks within a pack to the query embedding (cosine). */
    List<VectorChunk> search(String pack, float[] queryEmbedding, int k);
}
