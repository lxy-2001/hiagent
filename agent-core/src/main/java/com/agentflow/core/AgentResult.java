package com.agentflow.core;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.RunStatus;
import com.agentflow.core.runtime.TerminationReason;
import com.agentflow.core.rag.Citation;

import java.util.List;
import java.util.Objects;

public record AgentResult(
        String taskId,
        String finalAnswer,
        List<AgentStepRecord> steps,
        RunStatus status,
        TerminationReason terminationReason,
        TokenUsage usage,
        String diagnostic,
        List<Citation> citations
) {
    public AgentResult {
        citations = List.copyOf(citations);
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Objects.requireNonNull(steps, "steps must not be null");
        steps = List.copyOf(steps);
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(terminationReason, "terminationReason must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        if (citations.size() > 32 || (status != RunStatus.SUCCEEDED && !citations.isEmpty())) {
            throw new IllegalArgumentException("citations require successful result and at most 32 sources");
        }
        var citationIds = new java.util.HashSet<String>();
        var chunkIds = new java.util.HashSet<String>();
        for (var citation : citations) {
            if (!citationIds.add(citation.id()) || !chunkIds.add(citation.chunkId())
                    || !citations.get(0).snapshotId().equals(citation.snapshotId())) {
                throw new IllegalArgumentException("citations must have unique identifiers and one snapshot");
            }
        }
        validateStatusReason(status, terminationReason);
        finalAnswer = finalAnswer == null ? null : redact(finalAnswer);
        diagnostic = sanitize(diagnostic);
        if (status == RunStatus.SUCCEEDED) {
            if (terminationReason != TerminationReason.COMPLETED) {
                throw new IllegalArgumentException("successful result must terminate as COMPLETED");
            }
            if (finalAnswer == null || finalAnswer.isBlank()) {
                throw new IllegalArgumentException("successful result must have finalAnswer");
            }
        } else {
            if (terminationReason == TerminationReason.COMPLETED) {
                throw new IllegalArgumentException("non-success result cannot terminate as COMPLETED");
            }
            if (finalAnswer != null && !finalAnswer.isBlank()) {
                throw new IllegalArgumentException("non-success result must not have finalAnswer");
            }
        }
        if (status != RunStatus.SUCCEEDED && (finalAnswer == null || finalAnswer.isBlank())) {
            finalAnswer = null;
        }
    }

    public AgentResult(String taskId, String finalAnswer, List<AgentStepRecord> steps, RunStatus status,
                       TerminationReason terminationReason, TokenUsage usage, String diagnostic) {
        this(taskId, finalAnswer, steps, status, terminationReason, usage, diagnostic, List.of());
    }

    /** Compatibility constructor for the pre-Feature-002 result shape. */
    public AgentResult(String taskId, String finalAnswer, List<AgentStepRecord> steps) {
        this(taskId, finalAnswer, steps,
                finalAnswer == null || finalAnswer.isBlank() ? RunStatus.FAILED : RunStatus.SUCCEEDED,
                finalAnswer == null || finalAnswer.isBlank()
                        ? TerminationReason.MODEL_ERROR : TerminationReason.COMPLETED,
                TokenUsage.empty(),
                finalAnswer == null || finalAnswer.isBlank() ? "legacy result without terminal metadata" : "");
    }

    public static AgentResult success(String taskId, String answer, List<AgentStepRecord> steps,
                                      TokenUsage usage) {
        return new AgentResult(taskId, answer, steps, RunStatus.SUCCEEDED,
                TerminationReason.COMPLETED, usage, "");
    }

    public static AgentResult failure(String taskId, RunStatus status,
                                      TerminationReason reason, String diagnostic,
                                      List<AgentStepRecord> steps, TokenUsage usage) {
        if (status == RunStatus.SUCCEEDED) {
            throw new IllegalArgumentException("failure result cannot be SUCCEEDED");
        }
        return new AgentResult(taskId, null, steps, status, reason, usage, diagnostic);
    }

    private static void validateStatusReason(RunStatus status, TerminationReason reason) {
        boolean valid = switch (status) {
            case SUCCEEDED -> reason == TerminationReason.COMPLETED;
            case FAILED -> reason != TerminationReason.COMPLETED
                    && reason != TerminationReason.CANCELLED
                    && reason != TerminationReason.TIMED_OUT
                    && reason != TerminationReason.BUDGET_EXCEEDED
                    && reason != TerminationReason.CONTEXT_BUDGET_EXCEEDED;
            case CANCELLED -> reason == TerminationReason.CANCELLED;
            case TIMED_OUT -> reason == TerminationReason.TIMED_OUT;
            case BUDGET_EXCEEDED -> reason == TerminationReason.BUDGET_EXCEEDED
                    || reason == TerminationReason.CONTEXT_BUDGET_EXCEEDED;
        };
        if (!valid) {
            throw new IllegalArgumentException("status and terminationReason do not match");
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = redact(value);
        return sanitized.length() <= 1024 ? sanitized : sanitized.substring(0, 1024);
    }

    private static String redact(String value) {
        return value
                .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
    }
}
