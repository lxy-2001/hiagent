package com.agentflow.web.run;

import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepStatus;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.TerminationReason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RunResultProjector {

    private static final int MAX_ANSWER_CHARS = 65_536;
    private static final int MAX_STEPS = 256;
    private static final int MAX_TEXT_CHARS = 1_024;
    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_ID_CHARS = 128;
    private static final int MAX_PROJECTION_BYTES = 1_048_576;
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+");

    private final ObjectMapper objectMapper;

    public RunResultProjector() {
        this(new ObjectMapper());
    }

    RunResultProjector(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public enum FailureKind {
        QUEUE_TIMEOUT,
        DISPATCH_REJECTED,
        PERSISTENCE_UNAVAILABLE,
        INTERNAL_ERROR,
        PROCESS_INTERRUPTED
    }

    public record FinalProjection(
            String taskId,
            RunLifecycleStatus status,
            RunTerminationReason terminationReason,
            TerminationReason runtimeReason,
            String finalAnswer,
            TokenUsage usage,
            Instant finishedAt,
            boolean cancelRequested,
            boolean recordingComplete,
            String errorCode,
            List<ProjectedStep> steps
    ) {
        public FinalProjection {
            steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
        }
    }

    public record ProjectedStep(
            String taskId, int stepNo, String stepType, String name,
            String input, String output, String status, long latencyMs,
            Integer promptTokens, Integer completionTokens, String errorMessage,
            String decisionId, String callId, String errorCode, boolean terminal
    ) {
    }

    public FinalProjection project(String expectedTaskId, AgentResult result, Instant finishedAt,
                                   boolean cancelRequested, boolean observationComplete) {
        String taskId = requireTaskId(expectedTaskId);
        Instant terminalTime = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (result == null || !taskId.equals(result.taskId())) {
            return serviceFailure(taskId, RunTerminationReason.INVALID_RUNTIME_RESULT,
                    terminalTime, cancelRequested);
        }

        RunLifecycleStatus status = RunLifecycleStatus.valueOf(result.status().name());
        RunTerminationReason reason = RunTerminationReason.valueOf(result.terminationReason().name());
        if (status == RunLifecycleStatus.SUCCEEDED && result.finalAnswer().length() > MAX_ANSWER_CHARS) {
            return new FinalProjection(taskId, RunLifecycleStatus.FAILED,
                    RunTerminationReason.OUTPUT_TOO_LARGE, result.terminationReason(), null,
                    result.usage(), terminalTime, cancelRequested, false, "OUTPUT_TOO_LARGE", List.of());
        }
        StepProjection stepProjection = projectSteps(taskId, result);
        FinalProjection projection = new FinalProjection(taskId, status, reason, result.terminationReason(),
                result.finalAnswer(), result.usage(), terminalTime, cancelRequested, false,
                status == RunLifecycleStatus.SUCCEEDED ? null : reason.name(), stepProjection.steps());
        boolean complete = observationComplete && stepProjection.complete();
        return fitProjection(projection, complete);
    }

    public FinalProjection projectFailure(String expectedTaskId, FailureKind failure, Instant finishedAt,
                                          boolean cancelRequested) {
        String taskId = requireTaskId(expectedTaskId);
        Objects.requireNonNull(failure, "failure must not be null");
        RunTerminationReason reason = RunTerminationReason.valueOf(failure.name());
        return serviceFailure(taskId, reason,
                Objects.requireNonNull(finishedAt, "finishedAt must not be null"), cancelRequested);
    }

    private static FinalProjection serviceFailure(String taskId, RunTerminationReason reason,
                                                   Instant finishedAt, boolean cancelRequested) {
        RunLifecycleStatus status = switch (reason) {
            case QUEUE_TIMEOUT -> RunLifecycleStatus.TIMED_OUT;
            default -> RunLifecycleStatus.FAILED;
        };
        return new FinalProjection(taskId, status, reason, null, null, null, finishedAt,
                cancelRequested, false, reason.name(), List.of());
    }

    private StepProjection projectSteps(String taskId, AgentResult result) {
        List<ProjectedStep> projected = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        boolean structurallyComplete = !result.steps().isEmpty() && result.steps().size() <= MAX_STEPS;
        int expectedNumber = 1;
        int terminalCount = 0;
        AgentStepRecord terminal = null;

        for (AgentStepRecord source : result.steps()) {
            if (!taskId.equals(source.taskId()) || source.stepNo() > MAX_STEPS
                    || !seen.add(source.stepNo())) {
                structurallyComplete = false;
                continue;
            }
            if (source.stepNo() != expectedNumber) {
                structurallyComplete = false;
            }
            expectedNumber = source.stepNo() + 1;
            if (source.stepType() == AgentStepType.TERMINATION && source.terminal()) {
                terminalCount++;
                terminal = source;
            } else if (source.stepType() == AgentStepType.TERMINATION || source.terminal()) {
                structurallyComplete = false;
            }
            projected.add(projectStep(source));
        }

        structurallyComplete &= projected.size() == result.steps().size();
        structurallyComplete &= terminalCount == 1 && terminal != null
                && terminal.stepNo() == projected.size()
                && terminalMatches(terminal, result);
        return new StepProjection(List.copyOf(projected), structurallyComplete);
    }

    private static boolean terminalMatches(AgentStepRecord terminal, AgentResult result) {
        if (result.status() == com.agentflow.core.runtime.RunStatus.SUCCEEDED) {
            return terminal.status() == AgentStepStatus.SUCCESS
                    && result.terminationReason() == TerminationReason.COMPLETED
                    && terminal.errorCode() == null;
        }
        return terminal.status() == AgentStepStatus.FAILED
                && result.terminationReason().name().equals(terminal.errorCode());
    }

    private static ProjectedStep projectStep(AgentStepRecord source) {
        return new ProjectedStep(
                source.taskId(), source.stepNo(), source.stepType().name(),
                sanitize(source.toolName(), MAX_NAME_CHARS),
                sanitize(source.input(), MAX_TEXT_CHARS),
                sanitize(source.output(), MAX_TEXT_CHARS),
                source.status().name(), source.latencyMs(),
                source.promptTokens(), source.completionTokens(),
                sanitize(source.errorMessage(), MAX_TEXT_CHARS),
                sanitize(source.decisionId(), MAX_ID_CHARS),
                sanitize(source.callId(), MAX_ID_CHARS),
                sanitize(source.errorCode(), MAX_ID_CHARS), source.terminal());
    }

    private FinalProjection fitProjection(FinalProjection source, boolean complete) {
        List<ProjectedStep> steps = new ArrayList<>(source.steps());
        FinalProjection candidate = withSteps(source, steps, complete);
        while (!steps.isEmpty() && serializedSize(candidate) > MAX_PROJECTION_BYTES) {
            steps.remove(steps.size() - 1);
            candidate = withSteps(source, steps, false);
        }
        if (serializedSize(candidate) <= MAX_PROJECTION_BYTES) {
            return candidate;
        }
        return new FinalProjection(source.taskId(), RunLifecycleStatus.FAILED,
                RunTerminationReason.OUTPUT_TOO_LARGE, source.runtimeReason(), null,
                source.usage(), source.finishedAt(), source.cancelRequested(), false,
                RunTerminationReason.OUTPUT_TOO_LARGE.name(), List.of());
    }

    private static FinalProjection withSteps(FinalProjection source, List<ProjectedStep> steps,
                                             boolean recordingComplete) {
        return new FinalProjection(source.taskId(), source.status(), source.terminationReason(),
                source.runtimeReason(), source.finalAnswer(), source.usage(), source.finishedAt(),
                source.cancelRequested(), recordingComplete, source.errorCode(), steps);
    }

    private int serializedSize(FinalProjection projection) {
        try {
            return objectMapper.writeValueAsBytes(projection).length;
        } catch (JacksonException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static String sanitize(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String sanitized = ASSIGNMENT_SECRET.matcher(value).replaceAll("$1[redacted]");
        sanitized = BEARER_SECRET.matcher(sanitized).replaceAll("Bearer [redacted]");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1[redacted]");
        return truncateUtf16(sanitized, maxChars);
    }

    private static String truncateUtf16(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record StepProjection(List<ProjectedStep> steps, boolean complete) {
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "expectedTaskId must not be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("expectedTaskId must not be blank");
        }
        return taskId;
    }
}
