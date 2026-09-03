package com.agentflow.core;

import java.util.Objects;

public record AgentRequest(
        String taskId,
        String sessionId,
        String userId,
        String input
) {
    public AgentRequest {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(userId, "userId");
        Objects.requireNonNull(input, "input must not be null");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
