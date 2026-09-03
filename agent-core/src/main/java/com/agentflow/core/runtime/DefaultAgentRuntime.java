package com.agentflow.core.runtime;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.DefaultToolExecutor;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolResultNormalizer;
import com.agentflow.core.tool.ToolResultStatus;
import com.agentflow.core.tool.ValidationResult;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure Java, bounded, single-Tool-call-per-iteration Agent decision loop. */
public final class DefaultAgentRuntime implements com.agentflow.core.AgentRuntime {
    private final AgentModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final StepRecorder stepRecorder;
    private final ToolResultNormalizer resultNormalizer;
    private final TimeSource timeSource;

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer, TimeSource timeSource) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.stepRecorder = stepRecorder == null ? step -> { } : stepRecorder;
        this.resultNormalizer = resultNormalizer == null ? ToolResultNormalizer.IDENTITY : resultNormalizer;
        this.timeSource = timeSource == null ? TimeSource.system() : timeSource;
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder, resultNormalizer, TimeSource.system());
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder, ToolResultNormalizer.IDENTITY, TimeSource.system());
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               StepRecorder stepRecorder) {
        this(modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry), stepRecorder,
                new com.agentflow.core.tool.DefaultToolResultNormalizer(), TimeSource.system());
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               StepRecorder stepRecorder, TimeSource timeSource) {
        this(modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry), stepRecorder,
                new com.agentflow.core.tool.DefaultToolResultNormalizer(), timeSource);
    }

    @Override
    public AgentResult run(AgentRequest request, AgentEventSink eventSink, AgentRunOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.defaults() : options;
        RuntimeTrace trace = new RuntimeTrace(request.taskId(), stepRecorder, eventSink);
        BudgetTracker budget = new BudgetTracker(effectiveOptions.budget());
        Set<String> decisionIds = new HashSet<>();
        Set<String> callIds = new HashSet<>();
        long startedAt = timeSource.nanoTime();

        try {
            List<ToolDefinition> definitions = List.copyOf(
                    Objects.requireNonNull(toolRegistry.enabledDefinitions(), "enabled definitions must not be null"));
            AgentExecutionContext context = new AgentExecutionContext(request, definitions);

            for (int iteration = 1; ; iteration++) {
                Termination boundary = boundary(effectiveOptions, budget, iteration, startedAt);
                if (boundary != null) {
                    return finishFailure(request, trace, budget, boundary.reason(), boundary.status(), boundary.diagnostic());
                }

                AgentModelRequest modelRequest = context.modelRequest(iteration, budget.remainingCompletionTokens());
                long actionStarted = timeSource.nanoTime();
                ModelDecision decision;
                try {
                    decision = modelClient.decide(modelRequest);
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(), ex.getMessage(),
                            elapsed(actionStarted), AgentErrorCode.MODEL_ERROR.name(), null, null, false);
                    return finishFailure(request, trace, budget, TerminationReason.MODEL_ERROR,
                            RunStatus.FAILED, "model decision failed");
                }

                if (decision == null) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            "model returned no decision", elapsed(actionStarted),
                            AgentErrorCode.INVALID_DECISION.name(), null, null, false);
                    return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "model returned no decision");
                }
                if (!decisionIds.add(decision.decisionId())) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            "duplicate decision id", elapsed(actionStarted),
                            AgentErrorCode.INVALID_DECISION.name(), decision.decisionId(), null, false);
                    return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "duplicate decision id");
                }

                budget.record(decision.usage());
                trace.success(AgentStepType.MODEL_DECISION, "model", request.input(),
                        decisionDescription(decision), elapsed(actionStarted), decision.usage(),
                        decision.decisionId(), decision instanceof ToolCallDecision tool
                                ? tool.toolCall().callId() : null, false);

                // Retain a response that crossed a budget, but do not start its follow-up action.
                // Exact token exhaustion is still allowed to produce a Final answer or complete
                // the Tool action that the response explicitly requested; the next model boundary
                // is blocked by tokenBudgetReached().
                Termination afterDecision = afterDecisionBoundary(effectiveOptions, budget, startedAt);
                if (afterDecision != null) {
                    return finishFailure(request, trace, budget, afterDecision.reason(),
                            afterDecision.status(), afterDecision.diagnostic());
                }

                if (decision instanceof FinalAnswerDecision finalDecision) {
                    trace.success(AgentStepType.FINAL, "final-answer", request.input(),
                            finalDecision.answer(), 0, finalDecision.usage(),
                            finalDecision.decisionId(), null, false);
                    trace.terminate(TerminationReason.COMPLETED, RunStatus.SUCCEEDED, "completed");
                    return AgentResult.success(request.taskId(), finalDecision.answer(), trace.steps(), budget.snapshot());
                }
                if (!(decision instanceof ToolCallDecision toolDecision)) {
                    return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "unsupported model decision");
                }

                ToolCall call = toolDecision.toolCall();
                if (!callIds.add(call.callId())) {
                    trace.failure(AgentStepType.TOOL_CALL, call.name(), call.arguments().values().toString(),
                            "duplicate tool call id", 0, AgentErrorCode.INVALID_DECISION.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "duplicate tool call id");
                }

                // Check cancellation/timeout again immediately before the external Tool boundary.
                // Do not reject an exact token boundary here: the current Tool action is still
                // allowed, while the next model action will be rejected.
                Termination beforeTool = afterDecisionBoundary(effectiveOptions, budget, startedAt);
                if (beforeTool != null) {
                    return finishFailure(request, trace, budget, beforeTool.reason(),
                            beforeTool.status(), beforeTool.diagnostic());
                }
                context.confirmToolCall(call);
                trace.success(AgentStepType.TOOL_CALL, call.name(), call.arguments().values().toString(),
                        "requested", 0, null, toolDecision.decisionId(), call.callId(), false);

                ToolLookup lookup;
                try {
                    lookup = toolRegistry.lookup(call.name());
                } catch (RuntimeException ex) {
                    return toolFailure(request, trace, budget, call, toolDecision,
                            TerminationReason.UNKNOWN_TOOL, AgentErrorCode.UNKNOWN_TOOL.name(), ex.getMessage());
                }
                if (lookup == null || lookup.availability() == ToolAvailability.UNKNOWN) {
                    return toolFailure(request, trace, budget, call, toolDecision,
                            TerminationReason.UNKNOWN_TOOL, AgentErrorCode.UNKNOWN_TOOL.name(), "unknown tool");
                }
                if (lookup.availability() == ToolAvailability.DISABLED) {
                    return toolFailure(request, trace, budget, call, toolDecision,
                            TerminationReason.DISABLED_TOOL, AgentErrorCode.DISABLED_TOOL.name(), "tool is disabled");
                }

                ValidationResult validation;
                try {
                    validation = lookup.registration().definition().schema().validate(call.arguments());
                } catch (RuntimeException ex) {
                    return toolFailure(request, trace, budget, call, toolDecision,
                            TerminationReason.INVALID_TOOL_ARGUMENTS, AgentErrorCode.INVALID_TOOL_ARGUMENTS.name(),
                            ex.getMessage());
                }
                if (!validation.valid()) {
                    return toolFailure(request, trace, budget, call, toolDecision,
                            TerminationReason.INVALID_TOOL_ARGUMENTS, AgentErrorCode.INVALID_TOOL_ARGUMENTS.name(),
                            validation.violations().toString());
                }
                ToolCall validatedCall = new ToolCall(call.callId(), call.name(), validation.arguments());

                long toolStarted = timeSource.nanoTime();
                ToolResult rawResult;
                try {
                    rawResult = toolExecutor.execute(validatedCall,
                            new com.agentflow.core.tool.ToolContext(request.taskId(), request.sessionId(), request.userId()));
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            ex.getMessage(), elapsed(toolStarted), AgentErrorCode.TOOL_ERROR.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, budget, TerminationReason.TOOL_ERROR,
                            RunStatus.FAILED, "tool execution failed");
                }

                ToolResult normalized;
                try {
                    normalized = canonicalize(validatedCall, resultNormalizer.normalize(validatedCall, rawResult));
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            ex.getMessage(), elapsed(toolStarted), AgentErrorCode.TOOL_RESULT_INVALID.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, budget, TerminationReason.TOOL_RESULT_INVALID,
                            RunStatus.FAILED, "tool result invalid");
                }
                if (normalized.outputOrEmpty().length() > 8192) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            "tool result exceeds output limit", elapsed(toolStarted),
                            AgentErrorCode.TOOL_RESULT_TOO_LARGE.name(), toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, budget, TerminationReason.TOOL_RESULT_TOO_LARGE,
                            RunStatus.FAILED, "tool result exceeds output limit");
                }
                if (normalized.status() == ToolResultStatus.FAILED) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            normalized.diagnostic(), elapsed(toolStarted), normalized.errorCode(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, budget, terminationReasonFor(normalized.errorCode()),
                            RunStatus.FAILED, normalized.diagnostic());
                }
                trace.success(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                        normalized.outputOrEmpty(), elapsed(toolStarted), null, toolDecision.decisionId(), call.callId(), false);
                context.confirmToolResult(normalized);
            }
        } catch (RuntimeException ex) {
            trace.failure(AgentStepType.FAILURE, "runtime", request.input(), ex.getMessage(),
                    0, AgentErrorCode.INVALID_INPUT.name(), null, null, false);
            return finishFailure(request, trace, budget, TerminationReason.INVALID_INPUT,
                    RunStatus.FAILED, "runtime input or context invalid");
        }
    }

    private AgentResult toolFailure(AgentRequest request, RuntimeTrace trace, BudgetTracker budget,
                                    ToolCall call, ToolCallDecision decision, TerminationReason reason,
                                    String code, String diagnostic) {
        trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                diagnostic, 0, code, decision.decisionId(), call.callId(), false);
        return finishFailure(request, trace, budget, reason, RunStatus.FAILED, diagnostic);
    }

    private AgentResult finishFailure(AgentRequest request, RuntimeTrace trace, BudgetTracker budget,
                                      TerminationReason reason, RunStatus status, String diagnostic) {
        trace.terminate(reason, status, diagnostic);
        return AgentResult.failure(request.taskId(), status, reason, diagnostic, trace.steps(), budget.snapshot());
    }

    private static ToolResult canonicalize(ToolCall call, ToolResult result) {
        if (result == null) {
            throw new IllegalArgumentException("tool returned null result");
        }
        if (!call.name().equals(result.toolName())) {
            throw new IllegalArgumentException("tool result name does not match call");
        }
        if (result.callId() != null && !call.callId().equals(result.callId())) {
            throw new IllegalArgumentException("tool result call id does not match call");
        }
        if (result.status() == ToolResultStatus.SUCCESS
                && (result.output() == null || result.output().isBlank())) {
            throw new IllegalArgumentException("successful tool result must have output");
        }
        if (result.status() == ToolResultStatus.FAILED
                && (result.errorCode() == null || result.errorCode().isBlank())) {
            throw new IllegalArgumentException("failed tool result must have error code");
        }
        if (result.callId() == null) {
            return new ToolResult(result.toolName(), result.output(), result.status(), result.errorCode(),
                    result.diagnostic(), result.truncated(), call.callId());
        }
        return result;
    }

    private static TerminationReason terminationReasonFor(String errorCode) {
        if (errorCode == null) {
            return TerminationReason.TOOL_ERROR;
        }
        return switch (errorCode) {
            case "UNKNOWN_TOOL" -> TerminationReason.UNKNOWN_TOOL;
            case "DISABLED_TOOL" -> TerminationReason.DISABLED_TOOL;
            case "INVALID_TOOL_ARGUMENTS" -> TerminationReason.INVALID_TOOL_ARGUMENTS;
            case "TOOL_RESULT_TOO_LARGE" -> TerminationReason.TOOL_RESULT_TOO_LARGE;
            case "TOOL_RESULT_INVALID" -> TerminationReason.TOOL_RESULT_INVALID;
            default -> TerminationReason.TOOL_ERROR;
        };
    }

    private static String decisionDescription(ModelDecision decision) {
        return decision instanceof FinalAnswerDecision finalDecision
                ? finalDecision.answer()
                : ((ToolCallDecision) decision).toolCall().name();
    }

    private long elapsed(long started) {
        long delta;
        try {
            delta = Math.subtractExact(timeSource.nanoTime(), started);
        } catch (ArithmeticException ex) {
            delta = Long.MAX_VALUE;
        }
        return Math.max(0, Duration.ofNanos(delta).toMillis());
    }

    private Termination boundary(AgentRunOptions options, BudgetTracker budget, int iteration, long startedAt) {
        Termination timeBoundary = timeBoundary(options, startedAt);
        if (timeBoundary != null) {
            return timeBoundary;
        }
        if (budget.iterationExceeded(iteration) || budget.tokenBudgetReached()) {
            return budgetTermination();
        }
        return null;
    }

    private Termination afterDecisionBoundary(AgentRunOptions options, BudgetTracker budget, long startedAt) {
        Termination timeBoundary = timeBoundary(options, startedAt);
        if (timeBoundary != null) {
            return timeBoundary;
        }
        if (budget.tokenExceeded()) {
            return budgetTermination();
        }
        return null;
    }

    private Termination timeBoundary(AgentRunOptions options, long startedAt) {
        if (options.cancellationSignal().isCancelled()) {
            return new Termination(TerminationReason.CANCELLED, RunStatus.CANCELLED, "cancelled");
        }
        long elapsedNanos;
        try {
            elapsedNanos = Math.max(0, Math.subtractExact(timeSource.nanoTime(), startedAt));
        } catch (ArithmeticException ex) {
            elapsedNanos = Long.MAX_VALUE;
        }
        Duration maxDuration = options.budget().maxDuration();
        boolean timedOut;
        try {
            timedOut = elapsedNanos >= maxDuration.toNanos();
        } catch (ArithmeticException ex) {
            timedOut = false;
        }
        if (timedOut) {
            return new Termination(TerminationReason.TIMED_OUT, RunStatus.TIMED_OUT, "time budget exceeded");
        }
        return null;
    }

    private static Termination budgetTermination() {
        return new Termination(TerminationReason.BUDGET_EXCEEDED, RunStatus.BUDGET_EXCEEDED,
                "execution budget exceeded");
    }

    private record Termination(TerminationReason reason, RunStatus status, String diagnostic) { }
}
