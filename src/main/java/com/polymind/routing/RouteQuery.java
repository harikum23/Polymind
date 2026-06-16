package com.polymind.routing;

import java.util.List;

/**
 * Input to the router: the requested {@code model} control knob plus the signals needed for
 * classification and capability guards.
 *
 * @param model       concrete id | category alias | "auto" | null
 * @param taskHint    explicit task hint (metadata.task / X-Task), may be null
 * @param userMessages user-role message contents, for heuristic classification
 * @param needsTools  request carries tool definitions
 * @param force       forbid any routing fallback (metadata.force)
 */
public record RouteQuery(
        String model,
        String taskHint,
        List<String> userMessages,
        boolean needsTools,
        boolean force) {
}
