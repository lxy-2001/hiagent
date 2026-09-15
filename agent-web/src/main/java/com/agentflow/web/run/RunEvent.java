package com.agentflow.web.run;

import java.time.Instant;
import java.util.Objects;

public record RunEvent(String eventId, String taskId, String runId, Type type,
                       Instant occurredAt, Object payload) {

    public RunEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        if (!eventId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("eventId must be a positive decimal integer");
        }
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (!taskId.equals(runId)) {
            throw new IllegalArgumentException("taskId and runId must match");
        }
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    public enum Type {
        RUN_CREATED,
        RUN_STARTED,
        AGENT_STEP,
        RUN_TERMINATED
    }

    public record Draft(String taskId, String runId, Type type, Instant occurredAt, Object payload) {
        public Draft {
            Objects.requireNonNull(taskId, "taskId must not be null");
            if (!taskId.equals(runId)) {
                throw new IllegalArgumentException("taskId and runId must match");
            }
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
        }

        public RunEvent sequence(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("event id must be positive");
            }
            return new RunEvent(Long.toString(id), taskId, runId, type, occurredAt, payload);
        }
    }

    public record AgentStepPayload(String coreSequence, String stepType, String name,
                                   String summary, String correlationId,
                                   boolean runtimeTerminal, boolean summaryTruncated) {
    }
}
