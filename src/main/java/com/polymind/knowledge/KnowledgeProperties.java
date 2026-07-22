package com.polymind.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.knowledge")
public class KnowledgeProperties {

    private boolean enabled = false;
    private String embedModel = "nomic-embed";
    private int topK = 5;
    /**
     * Minimum cosine similarity (1 - distance) a retrieved chunk must clear to be injected.
     * Self-gates augmentation: an off-topic query (e.g. a JSON-validation call) whose best chunk
     * scores below this gets no context injected, so a default knowledge pack can be applied to all
     * traffic without polluting non-research calls. 0 disables gating.
     */
    private double minScore = 0.35;
    /** Token budget reserved for injected context (rough: 4 chars/token). */
    private int contextTokenBudget = 2000;
    /** Chunk size in characters for the indexer. */
    private int chunkChars = 1200;
    private int chunkOverlapChars = 150;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmbedModel() {
        return embedModel;
    }

    public void setEmbedModel(String embedModel) {
        this.embedModel = embedModel;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getContextTokenBudget() {
        return contextTokenBudget;
    }

    public void setContextTokenBudget(int contextTokenBudget) {
        this.contextTokenBudget = contextTokenBudget;
    }

    public int getChunkChars() {
        return chunkChars;
    }

    public void setChunkChars(int chunkChars) {
        this.chunkChars = chunkChars;
    }

    public int getChunkOverlapChars() {
        return chunkOverlapChars;
    }

    public void setChunkOverlapChars(int chunkOverlapChars) {
        this.chunkOverlapChars = chunkOverlapChars;
    }
}
