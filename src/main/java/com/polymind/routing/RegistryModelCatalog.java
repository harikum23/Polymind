package com.polymind.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs {@code GET /v1/models} from the registry: every concrete model (with capability metadata)
 * plus the four category aliases and {@code auto}.
 */
@Component
public class RegistryModelCatalog implements ModelCatalog {

    private static final List<String> ALIASES = List.of("auto", "chat", "code", "math", "reasoning");

    private final ModelRegistry registry;

    public RegistryModelCatalog(ModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ModelCard> list() {
        List<ModelCard> cards = new ArrayList<>();
        for (ModelSpec spec : registry.all()) {
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("engine", spec.engine());
            if (spec.isEmbed()) {
                caps.put("role", "embed");
            } else {
                caps.put("ctx", spec.ctx());
                caps.put("supports_tools", spec.supportsTools());
                caps.put("scores", spec.scores());
            }
            cards.add(new ModelCard(spec.id(), "model", caps));
        }
        for (String alias : ALIASES) {
            cards.add(new ModelCard(alias, "alias", Map.of("description", aliasDescription(alias))));
        }
        return cards;
    }

    private String aliasDescription(String alias) {
        return switch (alias) {
            case "auto" -> "Classify the request and pick the best-scoring model";
            default -> "Best-scoring model for the '" + alias + "' task category";
        };
    }
}
