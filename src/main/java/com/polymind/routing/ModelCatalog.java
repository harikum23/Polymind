package com.polymind.routing;

import java.util.List;
import java.util.Map;

/**
 * Supplies the {@code /v1/models} listing using neutral {@link ModelCard} records (no web DTOs),
 * so the web edge can map them without crossing module-internal boundaries.
 */
public interface ModelCatalog {

    List<ModelCard> list();

    /**
     * A model or category alias with capability metadata.
     *
     * @param id           model id or alias
     * @param kind         {@code "model"} or {@code "alias"}
     * @param capabilities engine/ctx/supports_tools/scores (models) or description (aliases)
     */
    record ModelCard(String id, String kind, Map<String, Object> capabilities) {}
}
