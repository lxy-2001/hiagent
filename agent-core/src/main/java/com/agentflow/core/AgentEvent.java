package com.agentflow.core;

import java.time.Instant;
import java.util.Objects;

public record AgentEvent(
        String taskId,
        AgentStepType type,
        String name,
        String content,
        Instant occurredAt,
        long sequence,
        String correlationId,
        boolean terminal
) {
    public AgentEvent {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    public AgentEvent(String taskId, AgentStepType type, String name, String content,
                      Instant occurredAt) {
        this(taskId, type, name, content, occurredAt, 0, null, false);
    }

    public static AgentEvent now(String taskId, AgentStepType type, String name, String content) {
        return new AgentEvent(taskId, type, name, content, Instant.now());
    }

    public static AgentEvent traced(String taskId, AgentStepType type, String name, String content,
                                    long sequence, String correlationId, boolean terminal) {
        return new AgentEvent(taskId, type, name, content, Instant.now(), sequence,
                correlationId, terminal);
    }
}
