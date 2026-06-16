package com.polymind.routing;

import java.util.Map;

/**
 * A registered model from {@code models.yaml}. {@code scores} holds per-task capability scores
 * (chat/code/math/reasoning); {@code role} is {@code "embed"} for embedding models.
 */
public record ModelSpec(
        String id,
        String engine,
        int ctx,
        boolean supportsTools,
        String role,
        Map<String, Integer> scores) {

    public boolean isEmbed() {
        return "embed".equalsIgnoreCase(role);
    }

    public boolean isChatModel() {
        return !isEmbed();
    }

    public int scoreFor(String category) {
        return scores == null ? 0 : scores.getOrDefault(category, 0);
    }
}
