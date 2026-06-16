package com.polymind.routing;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process model registry loaded from {@code models.yaml} (ARCHITECTURE.md §4.1). Hot-reloadable
 * via {@link #reload()}. Published service for routing decisions and the {@code /v1/models} listing.
 */
@Component
@EnableConfigurationProperties({RegistryProperties.class, RoutingProperties.class})
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ResourceLoader resourceLoader;
    private final RegistryProperties props;
    private final AtomicReference<Map<String, ModelSpec>> models = new AtomicReference<>(Map.of());

    public ModelRegistry(ResourceLoader resourceLoader, RegistryProperties props) {
        this.resourceLoader = resourceLoader;
        this.props = props;
    }

    @PostConstruct
    public void reload() {
        Resource resource = resourceLoader.getResource(props.getLocation());
        Map<String, ModelSpec> loaded = new LinkedHashMap<>();
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object modelsNode = root == null ? null : root.get("models");
            if (modelsNode instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String id = String.valueOf(e.getKey());
                    loaded.put(id, parse(id, (Map<?, ?>) e.getValue()));
                }
            }
            models.set(loaded);
            log.info("Loaded {} models from {}", loaded.size(), props.getLocation());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load model registry from " + props.getLocation(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private ModelSpec parse(String id, Map<?, ?> node) {
        String engine = str(node, "engine", "ollama");
        int ctx = node.get("ctx") instanceof Number n ? n.intValue() : 0;
        boolean supportsTools = Boolean.TRUE.equals(node.get("supports_tools"));
        String role = str(node, "role", null);
        Map<String, Integer> scores = new LinkedHashMap<>();
        Object scoresNode = node.get("scores");
        if (scoresNode instanceof Map<?, ?> sm) {
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                scores.put(String.valueOf(e.getKey()),
                        e.getValue() instanceof Number n ? n.intValue() : 0);
            }
        }
        return new ModelSpec(id, engine, ctx, supportsTools, role, scores);
    }

    private String str(Map<?, ?> node, String key, String def) {
        Object v = node.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public Optional<ModelSpec> find(String id) {
        return Optional.ofNullable(models.get().get(id));
    }

    public boolean contains(String id) {
        return models.get().containsKey(id);
    }

    public Collection<ModelSpec> all() {
        return List.copyOf(models.get().values());
    }

    public List<ModelSpec> chatModels() {
        return new ArrayList<>(models.get().values().stream().filter(ModelSpec::isChatModel).toList());
    }

    public Optional<ModelSpec> defaultEmbedModel() {
        return models.get().values().stream().filter(ModelSpec::isEmbed).findFirst();
    }
}
