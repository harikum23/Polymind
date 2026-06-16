package com.polymind.inference;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves an {@link Engine} by name. Published service for other modules (routing, knowledge,
 * agent) to obtain the engine for a chosen model without touching engine adapter internals.
 */
@Component
public class EngineRegistry {

    private final Map<String, Engine> engines;

    public EngineRegistry(List<Engine> engineBeans) {
        this.engines = engineBeans.stream()
                .collect(Collectors.toMap(Engine::name, Function.identity()));
    }

    public Engine require(String engineName) {
        Engine engine = engines.get(engineName);
        if (engine == null) {
            throw new IllegalArgumentException("No inference engine registered named '" + engineName + "'");
        }
        return engine;
    }

    public boolean has(String engineName) {
        return engines.containsKey(engineName);
    }
}
