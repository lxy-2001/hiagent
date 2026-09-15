package com.agentflow.web.run;

import com.agentflow.core.AgentEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class RunEventProjector {

    private static final String UNAVAILABLE = "EVENT_CONTENT_UNAVAILABLE";
    private static final Pattern ASSIGNMENT_SECRET = Pattern.compile(
            "(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+");

    public Projection project(String expectedTaskId, AgentEvent event, Instant fallbackOccurredAt) {
        String taskId = requireTaskId(expectedTaskId);
        Instant fallback = Objects.requireNonNull(fallbackOccurredAt,
                "fallbackOccurredAt must not be null");
        if (event == null) {
            return new Projection(Optional.of(draft(taskId, fallback, "UNKNOWN", "event",
                    UNAVAILABLE, "0", null, false, true)), false);
        }
        if (!taskId.equals(event.taskId())) {
            return new Projection(Optional.empty(), false);
        }
        try {
            Sanitized summary = sanitizeSummary(event.content());
            RunEvent.Draft draft = draft(taskId, event.occurredAt(), event.type().name(),
                    truncate(event.name(), 100), summary.value(), Long.toString(event.sequence()),
                    truncate(event.correlationId(), 128), event.terminal(), summary.truncated());
            return new Projection(Optional.of(draft), true);
        } catch (RuntimeException exception) {
            return new Projection(Optional.of(draft(taskId, fallback, "UNKNOWN", "event",
                    UNAVAILABLE, "0", null, false, true)), false);
        }
    }

    private static RunEvent.Draft draft(String taskId, Instant occurredAt, String stepType,
                                        String name, String summary, String coreSequence,
                                        String correlationId, boolean terminal, boolean truncated) {
        RunEvent.AgentStepPayload payload = new RunEvent.AgentStepPayload(coreSequence, stepType,
                name, summary, correlationId, terminal, truncated);
        return new RunEvent.Draft(taskId, taskId, RunEvent.Type.AGENT_STEP, occurredAt, payload);
    }

    private static Sanitized sanitizeSummary(String value) {
        String sanitized = ASSIGNMENT_SECRET.matcher(value).replaceAll("$1[redacted]");
        sanitized = BEARER_SECRET.matcher(sanitized).replaceAll("Bearer [redacted]");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1[redacted]");
        boolean truncated = sanitized.length() > 1_024;
        return new Sanitized(truncate(sanitized, 1_024), truncated);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "expectedTaskId must not be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("expectedTaskId must not be blank");
        }
        return taskId;
    }

    private record Sanitized(String value, boolean truncated) {
    }

    public record Projection(Optional<RunEvent.Draft> event, boolean observationComplete) {
    }
}
