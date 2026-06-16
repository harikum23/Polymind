package com.polymind.routing;

/** Outcome of a routing decision: the chosen model, its engine, and a human-readable reason. */
public record RouteDecision(String modelId, String engine, String reason, TaskCategory category) {}
