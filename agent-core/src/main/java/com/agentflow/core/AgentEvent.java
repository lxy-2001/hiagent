package com.agentflow.core;

import java.time.Instant;

public record AgentEvent(
        String taskId,
        AgentStepType type,
        String name,
        String content,
        Instant occurredAt
) {
    public static AgentEvent now(String taskId, AgentStepType type, String name, String content) {
        return new AgentEvent(taskId, type, name, content, Instant.now());
    }
}
