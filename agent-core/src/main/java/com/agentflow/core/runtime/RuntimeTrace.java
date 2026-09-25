package com.agentflow.core.runtime;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepStatus;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.step.StepRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Run-local trace writer. Observer failures are isolated from Runtime state. */
public final class RuntimeTrace {
    private final String taskId;
    private final StepRecorder recorder;
    private final AgentEventSink eventSink;
    private final List<AgentStepRecord> steps = new ArrayList<>();
    private final List<com.agentflow.core.tool.ToolInvocationRecord> invocations = new ArrayList<>();
    private InvocationTrace activeInvocation;
    InvocationTrace beginInvocation(com.agentflow.core.tool.PreparedToolCall prepared, com.agentflow.core.tool.ToolPolicyDecision policy) {
        completeInvocation(null);
        activeInvocation = new InvocationTrace(prepared,policy);
        return activeInvocation;
    }
    void completeInvocation(String errorCode) {
        if (activeInvocation != null) {
            if (activeInvocation.errorCode == null) activeInvocation.errorCode=errorCode;
            invocations.add(activeInvocation.snapshot());
            activeInvocation=null;
        }
    }
    public List<com.agentflow.core.tool.ToolInvocationRecord> toolInvocations() { return List.copyOf(invocations); }
    private int nextStepNo = 1;
    private boolean terminated;

    public RuntimeTrace(String taskId, StepRecorder recorder, AgentEventSink eventSink) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.recorder = recorder == null ? step -> { } : recorder;
        this.eventSink = eventSink == null ? AgentEventSink.NOOP : eventSink;
    }

    public AgentStepRecord success(AgentStepType type, String name, String input, String output,
                                   long latencyMs, TokenUsage usage, String decisionId,
                                   String callId, boolean terminal) {
        AgentStepRecord step = AgentStepRecord.success(taskId, nextStepNo++, type, name, safe(input),
                output == null ? "" : safe(output), Math.max(0, latencyMs),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(), decisionId, callId, terminal);
        append(step);
        return step;
    }

    public AgentStepRecord failure(AgentStepType type, String name, String input, String message,
                                   long latencyMs, String errorCode, String decisionId,
                                   String callId, boolean terminal) {
        if ("knowledge.search".equals(name)) { message = "retrieval failed"; }
        AgentStepRecord step = AgentStepRecord.failed(taskId, nextStepNo++, type, name, safe(input),
                safe(message), Math.max(0, latencyMs), errorCode, decisionId, callId, terminal);
        append(step);
        return step;
    }

    /** Emits at most one terminal step/event and returns whether this call won the guard. */
    public boolean terminate(TerminationReason reason, RunStatus status, String diagnostic) {
        if (terminated) {
            return false;
        }
        completeInvocation(reason == TerminationReason.COMPLETED ? null : reason.name());
        terminated = true;
        String content = reason.name() + (diagnostic == null || diagnostic.isBlank()
                ? "" : ": " + safe(diagnostic));
        AgentStepRecord step;
        if (status == RunStatus.SUCCEEDED) {
            step = AgentStepRecord.success(taskId, nextStepNo++, AgentStepType.TERMINATION,
                    "termination", null, content, 0, null, null, null, null, true);
        } else {
            step = AgentStepRecord.failed(taskId, nextStepNo++, AgentStepType.TERMINATION,
                    "termination", null, content, 0, reason.name(), null, null, true);
        }
        append(step);
        return true;
    }

    public boolean terminated() {
        return terminated;
    }

    public List<AgentStepRecord> steps() {
        return List.copyOf(steps);
    }

    private void append(AgentStepRecord step) {
        steps.add(step);
        try {
            recorder.record(step);
        } catch (RuntimeException ignored) {
            // Observers cannot mutate the already confirmed run state.
        }
        String name = step.toolName() == null ? step.stepType().name() : step.toolName();
        String content = step.status() == AgentStepStatus.SUCCESS
                ? safe(step.output()) : safe(step.errorMessage());
        try {
            eventSink.publish(AgentEvent.traced(taskId, step.stepType(), name, content,
                    step.stepNo(), step.callId() == null ? step.decisionId() : step.callId(),
                    step.terminal()));
        } catch (RuntimeException ignored) {
            // Event delivery is best effort for this synchronous run.
        }
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value
                .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
        return sanitized.length() <= 1024 ? sanitized : sanitized.substring(0, 1024);
    }
}
