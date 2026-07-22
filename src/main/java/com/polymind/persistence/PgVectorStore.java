package com.polymind.persistence;

import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Postgres + pgvector implementation of {@link VectorStore} (ARCHITECTURE.md §10). Bootstraps the
 * extension + table on demand (dimension inferred from the first stored vector) and uses cosine
 * distance ({@code <=>}) for top-K retrieval. Active only when {@code polymind.knowledge.enabled=true}.
 */
@Repository
@ConditionalOnProperty(prefix = "polymind.knowledge", name = "enabled", havingValue = "true")
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final JdbcTemplate jdbc;
    private volatile boolean initialized = false;

    public PgVectorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attach to an already-created {@code knowledge_chunk} table on read. Without this, a restart
     * leaves {@code initialized=false} until the next write, so previously-indexed packs (which live
     * in Postgres, not this process) would read back empty until the next reindex — blanking the
     * knowledge layer on every Polymind restart.
     */
    private void attachExistingSchema() {
        if (initialized) {
            return;
        }
        try {
            String table = jdbc.queryForObject(
                    "SELECT to_regclass('public.knowledge_chunk')::text", String.class);
            if (table != null) {
                initialized = true;
                log.info("Attached to existing knowledge_chunk table on startup");
            }
        } catch (Exception e) {
            log.debug("knowledge_chunk existence probe failed: {}", e.getMessage());
        }
    }

    private synchronized void ensureSchema(int dimension) {
        if (initialized) {
            return;
        }
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunk (
                    id        BIGSERIAL PRIMARY KEY,
                    pack      VARCHAR(128) NOT NULL,
                    source    VARCHAR(512),
                    content   TEXT NOT NULL,
                    embedding vector(%d) NOT NULL
                )""".formatted(dimension));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_pack ON knowledge_chunk (pack)");
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_knowledge_embedding
                ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)""");
        initialized = true;
        log.info("knowledge_chunk schema ready (dim={})", dimension);
    }

    @Override
    public long count(String pack) {
        attachExistingSchema();
        if (!initialized) {
            return 0;
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk WHERE pack = ?", Long.class, pack);
        return n == null ? 0 : n;
    }

    @Override
    public void store(VectorChunk chunk, float[] embedding) {
        ensureSchema(embedding.length);
        jdbc.update("INSERT INTO knowledge_chunk (pack, source, content, embedding) VALUES (?, ?, ?, ?)",
                chunk.pack(), chunk.source(), chunk.content(), new PGvector(embedding));
    }

    @Override
    public void clearPack(String pack) {
        attachExistingSchema();
        if (initialized) {
            jdbc.update("DELETE FROM knowledge_chunk WHERE pack = ?", pack);
        }
    }

    @Override
    public List<VectorChunk> search(String pack, float[] queryEmbedding, int k) {
        attachExistingSchema();
        if (!initialized) {
            return List.of();
        }
        PGvector q = new PGvector(queryEmbedding);
        return jdbc.query("""
                        SELECT id, pack, source, content, 1 - (embedding <=> ?) AS score
                        FROM knowledge_chunk
                        WHERE pack = ?
                        ORDER BY embedding <=> ?
                        LIMIT ?""",
                (rs, n) -> new VectorChunk(
                        rs.getLong("id"), rs.getString("pack"), rs.getString("source"),
                        rs.getString("content"), rs.getDouble("score")),
                q, pack, q, k);
    }
}
