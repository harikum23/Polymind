package com.polymind.routing;

import java.util.List;

/**
 * Pluggable task classification strategy (ARCHITECTURE.md §4.3). Implementations may evolve from
 * heuristics to an LLM/embedding-similarity router without touching callers.
 */
public interface TaskClassifier {

    /** Classify the request into a task category given the conversation messages. */
    TaskCategory classify(List<String> userMessages);
}
