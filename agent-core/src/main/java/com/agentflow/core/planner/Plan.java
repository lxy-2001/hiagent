package com.agentflow.core.planner;

import java.util.List;

public record Plan(String reasoning, List<String> toolNames) {

    @Override
    public String toString() {
        return "Plan{reasoning='%s', toolNames=%s}".formatted(reasoning, toolNames);
    }
}
