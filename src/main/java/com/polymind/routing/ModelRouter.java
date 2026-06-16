package com.polymind.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The smart switch (ARCHITECTURE.md §4.2). Selection precedence (first match wins):
 * <ol>
 *   <li>Forced explicit model id -> use it, bypassing classification.</li>
 *   <li>Forced category alias (chat/code/math/reasoning) -> best-scoring available model.</li>
 *   <li>auto / omitted -> classify the request, then best-scoring model for that category.</li>
 * </ol>
 * Capability guards (§4.4): if the request needs tools, only {@code supports_tools} models qualify;
 * falls back to the next-best qualifying model unless {@code force} forbids fallback.
 */
@Service
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ModelRegistry registry;
    private final TaskClassifier classifier;

    public ModelRouter(ModelRegistry registry, TaskClassifier classifier) {
        this.registry = registry;
        this.classifier = classifier;
    }

    public RouteDecision route(RouteQuery query) {
        String model = query.model();

        // 1. Forced explicit model id.
        if (model != null && !model.isBlank() && !model.equals("auto") && !TaskCategory.isAlias(model)) {
            ModelSpec spec = registry.find(model)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown model id: " + model));
            if (query.needsTools() && !spec.supportsTools() && query.force()) {
                throw new IllegalArgumentException(
                        "Model '" + model + "' does not support tools and force=true forbids fallback");
            }
            return new RouteDecision(spec.id(), spec.engine(),
                    "forced-explicit-id", TaskCategory.CHAT);
        }

        // 2. Forced category alias.
        if (TaskCategory.isAlias(model)) {
            TaskCategory category = TaskCategory.from(model);
            return pickBest(category, query, "forced-category:" + category.key());
        }

        // 3. auto -> classify -> best.
        TaskCategory category = resolveCategory(query);
        return pickBest(category, query, "auto-classified:" + category.key());
    }

    private TaskCategory resolveCategory(RouteQuery query) {
        // 4.3 step 1: explicit hint wins.
        if (query.taskHint() != null && TaskCategory.isAlias(query.taskHint())) {
            return TaskCategory.from(query.taskHint());
        }
        // 4.3 step 2: heuristics (LLM classifier is a later upgrade).
        return classifier.classify(query.userMessages());
    }

    private RouteDecision pickBest(TaskCategory category, RouteQuery query, String baseReason) {
        List<ModelSpec> candidates = registry.chatModels().stream()
                .filter(spec -> !query.needsTools() || spec.supportsTools()) // §4.4 capability guard
                .sorted(Comparator.comparingInt((ModelSpec s) -> s.scoreFor(category.key())).reversed())
                .toList();

        Optional<ModelSpec> best = candidates.stream().findFirst();
        if (best.isEmpty()) {
            if (query.force()) {
                throw new IllegalArgumentException(
                        "No model satisfies the request constraints and force=true forbids fallback");
            }
            // last-resort fallback: any chat model.
            best = registry.chatModels().stream().findFirst();
        }
        ModelSpec chosen = best.orElseThrow(() ->
                new IllegalStateException("No chat models registered"));
        String reason = baseReason + (query.needsTools() ? "+tools-guard" : "");
        log.debug("Routed model={} engine={} reason={}", chosen.id(), chosen.engine(), reason);
        return new RouteDecision(chosen.id(), chosen.engine(), reason, category);
    }
}
