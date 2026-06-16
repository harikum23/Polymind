package com.polymind.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Records routing decisions as Micrometer counters (chosen model, task category, reason) so
 * routing quality is observable (ARCHITECTURE.md §7 step 9, §13 "log chosen model + reason").
 */
@Component
public class RoutingMetrics {

    private final MeterRegistry registry;

    public RoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRoute(String model, String category, String reason) {
        registry.counter("polymind.routing.decisions",
                "model", model,
                "category", category,
                "reason", normalizeReason(reason)).increment();
    }

    private String normalizeReason(String reason) {
        int idx = reason.indexOf(':');
        return idx > 0 ? reason.substring(0, idx) : reason;
    }
}
