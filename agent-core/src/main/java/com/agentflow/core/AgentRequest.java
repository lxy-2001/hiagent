package com.agentflow.core;

import com.agentflow.core.context.ContextSeed;
import java.util.Objects;

public record AgentRequest(
        String taskId,
        String sessionId,
        String userId,
        String input,
        ContextSeed contextSeed
) {
    public AgentRequest {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(userId, "userId");
        Objects.requireNonNull(input, "input must not be null");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        Objects.requireNonNull(contextSeed, "contextSeed must not be null");
        if (contextSeed.turns().stream().anyMatch(turn -> taskId.equals(turn.runId()))) {
            throw new IllegalArgumentException("context history must not contain the current run");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public AgentRequest(String taskId, String sessionId, String userId, String input) {
        this(taskId, sessionId, userId, input, ContextSeed.empty());
    }
}
