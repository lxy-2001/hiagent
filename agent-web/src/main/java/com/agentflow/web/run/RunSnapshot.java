package com.agentflow.web.run;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.TerminationReason;

import java.time.Instant;
import java.util.Objects;

public record RunSnapshot(
        String taskId,
        String runId,
        String sessionId,
        RunLifecycleStatus status,
        String input,
        String finalAnswer,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        boolean cancelRequested,
        RunTerminationReason terminationReason,
        TerminationReason runtimeReason,
        String errorCode,
        boolean recordingComplete,
        TokenUsage usage,
        boolean requireEvidence,
        java.util.List<com.agentflow.core.rag.Citation> citations
) {
    public RunSnapshot(String taskId, String runId, String sessionId, RunLifecycleStatus status,
            String input, String finalAnswer, Instant createdAt, Instant updatedAt, Instant startedAt,
            Instant finishedAt, boolean cancelRequested, RunTerminationReason terminationReason,
            TerminationReason runtimeReason, String errorCode, boolean recordingComplete, TokenUsage usage) {
        this(taskId, runId, sessionId, status, input, finalAnswer, createdAt, updatedAt, startedAt,
                finishedAt, cancelRequested, terminationReason, runtimeReason, errorCode, recordingComplete,
                usage, false, java.util.List.of());
    }
    public RunSnapshot {
        citations = java.util.List.copyOf(citations);
        if (status != RunLifecycleStatus.SUCCEEDED && !citations.isEmpty()) {
            throw new IllegalArgumentException("only successful snapshots have citations");
        }
        requireNonBlank(taskId, "taskId");
        requireNonBlank(runId, "runId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(input, "input");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (!taskId.equals(runId)) {
            throw new IllegalArgumentException("runId must equal taskId");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }
        if (finishedAt != null && (finishedAt.isBefore(createdAt)
                || startedAt != null && finishedAt.isBefore(startedAt))) {
            throw new IllegalArgumentException("finishedAt must not be before the run start");
        }

        if (!status.isTerminal()) {
            validateNonTerminal(status, finalAnswer, startedAt, finishedAt, terminationReason, runtimeReason,
                    errorCode, recordingComplete, usage);
        } else {
            Objects.requireNonNull(finishedAt, "terminal snapshot finishedAt must not be null");
            Objects.requireNonNull(terminationReason, "terminal snapshot terminationReason must not be null");
            validateStatusReason(status, terminationReason);
            if (status == RunLifecycleStatus.SUCCEEDED) {
                if (finalAnswer == null || finalAnswer.isBlank()) {
                    throw new IllegalArgumentException("successful snapshot answer must not be blank");
                }
                if (errorCode != null) {
                    throw new IllegalArgumentException("successful snapshot errorCode must be null");
                }
            } else {
                if (finalAnswer != null) {
                    throw new IllegalArgumentException("non-success snapshot answer must be null");
                }
                if (!terminationReason.name().equals(errorCode)) {
                    throw new IllegalArgumentException("errorCode must equal terminationReason for non-success snapshots");
                }
            }
        }
    }

    private static void validateNonTerminal(RunLifecycleStatus status, String finalAnswer,
                                            Instant startedAt, Instant finishedAt,
                                            RunTerminationReason terminationReason,
                                            TerminationReason runtimeReason, String errorCode,
                                            boolean recordingComplete, TokenUsage usage) {
        if (status == RunLifecycleStatus.QUEUED && startedAt != null) {
            throw new IllegalArgumentException("queued snapshot startedAt must be null");
        }
        if (status == RunLifecycleStatus.RUNNING && startedAt == null) {
            throw new IllegalArgumentException("running snapshot startedAt must not be null");
        }
        if (finalAnswer != null || finishedAt != null || terminationReason != null || runtimeReason != null
                || errorCode != null || recordingComplete || usage != null) {
            throw new IllegalArgumentException("non-terminal snapshot cannot contain terminal result fields");
        }
    }

    private static void validateStatusReason(RunLifecycleStatus status, RunTerminationReason reason) {
        boolean valid = switch (status) {
            case SUCCEEDED -> reason == RunTerminationReason.COMPLETED;
            case FAILED -> reason != RunTerminationReason.COMPLETED
                    && reason != RunTerminationReason.CANCELLED
                    && reason != RunTerminationReason.TIMED_OUT
                    && reason != RunTerminationReason.BUDGET_EXCEEDED
                    && reason != RunTerminationReason.QUEUE_TIMEOUT;
            case CANCELLED -> reason == RunTerminationReason.CANCELLED;
            case TIMED_OUT -> reason == RunTerminationReason.TIMED_OUT
                    || reason == RunTerminationReason.QUEUE_TIMEOUT;
            case BUDGET_EXCEEDED -> reason == RunTerminationReason.BUDGET_EXCEEDED;
            case QUEUED, RUNNING -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("status and terminationReason do not match");
        }
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
