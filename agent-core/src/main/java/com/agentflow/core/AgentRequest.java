package com.agentflow.core;

import java.util.Objects;

public record AgentRequest(
        String taskId,
        String sessionId,
        String userId,
        String input
) {
    public AgentRequest {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(input, "input must not be null");
    }
}
