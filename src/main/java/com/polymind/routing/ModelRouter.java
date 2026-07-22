package com.polymind.routing;

import com.polymind.inference.EngineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final EngineRegistry engines;

    public ModelRouter(ModelRegistry registry, TaskClassifier classifier, EngineRegistry engines) {
        this.registry = registry;
        this.classifier = classifier;
        this.engines = engines;
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
            // A forced id that the engine can't currently serve would otherwise fail as an opaque
            // downstream 404 — surface it up front with an actionable message.
            if (!isAvailable(spec)) {
                throw new IllegalArgumentException("Model '" + model + "' is registered but not "
                        + "currently available on engine '" + spec.engine() + "' (not pulled/loaded)");
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
        List<ModelSpec> guarded = registry.chatModels().stream()
                .filter(spec -> !query.needsTools() || spec.supportsTools()) // §4.4 capability guard
                .sorted(Comparator.comparingInt((ModelSpec s) -> s.scoreFor(category.key())).reversed())
                .toList();

        // §4.2: prefer the best-scoring *available* model. Registered-but-not-pulled models (e.g.
        // gemma2-9b when only qwen2.5-7b is loaded) are skipped so 'auto'/category routing never
        // resolves to a model the engine will 404 on. Degrade gracefully if none are available.
        List<ModelSpec> available = guarded.stream().filter(this::isAvailable).toList();
        boolean availabilityKnown = !available.isEmpty();
        List<ModelSpec> candidates = availabilityKnown ? available : guarded;
        if (availabilityKnown && available.size() < guarded.size()) {
            log.debug("Availability filter dropped {} unavailable model(s) for category={}",
                    guarded.size() - available.size(), category.key());
        }

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
        String reason = baseReason + (query.needsTools() ? "+tools-guard" : "")
                + (availabilityKnown ? "+avail-filtered" : "");
        log.debug("Routed model={} engine={} reason={}", chosen.id(), chosen.engine(), reason);
        return new RouteDecision(chosen.id(), chosen.engine(), reason, category);
    }

    /**
     * True if the model's engine currently serves it. An empty availability set means "unknown"
     * (engine unreachable) — treated as available so a transient outage never blanks out routing.
     * Matches Ollama tag naming: {@code qwen2.5-7b} matches {@code qwen2.5-7b:latest}.
     */
    private boolean isAvailable(ModelSpec spec) {
        if (!engines.has(spec.engine())) {
            return false;
        }
        Set<String> names = engines.require(spec.engine()).availableModels();
        if (names.isEmpty()) {
            return true; // availability unknown -> do not filter
        }
        String id = spec.id();
        return names.stream().anyMatch(n ->
                n.equals(id) || n.equals(id + ":latest") || n.startsWith(id + ":"));
    }
}
