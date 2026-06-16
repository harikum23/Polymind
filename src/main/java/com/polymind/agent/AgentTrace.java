package com.polymind.agent;

import java.util.ArrayList;
import java.util.List;

/** Records each step of an agent run for observability/debugging (ARCHITECTURE.md §6 "trace"). */
public record AgentTrace(List<Step> steps) {

    public AgentTrace() {
        this(new ArrayList<>());
    }

    public void add(String type, String detail) {
        steps.add(new Step(steps.size() + 1, type, detail));
    }

    public record Step(int index, String type, String detail) {}
}
