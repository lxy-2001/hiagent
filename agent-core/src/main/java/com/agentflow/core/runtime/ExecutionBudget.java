package com.agentflow.core.runtime;

import java.time.Duration;
import java.util.Objects;

public record ExecutionBudget(
        int maxIterations,
        Duration maxDuration,
        int maxPromptTokens,
        int maxCompletionTokens
) {
    public ExecutionBudget {
        if (maxIterations < 0 || maxPromptTokens < 0 || maxCompletionTokens < 0) {
            throw new IllegalArgumentException("budget limits must not be negative");
        }
        Objects.requireNonNull(maxDuration, "maxDuration must not be null");
        if (maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must not be negative");
        }
    }

    public static ExecutionBudget defaults() {
        return new ExecutionBudget(8, Duration.ofSeconds(30), 4096, 2048);
    }
}
