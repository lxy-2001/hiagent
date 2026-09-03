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

/** Pure Java, single-call-per-iteration Agent decision loop. */
public final class DefaultAgentRuntime implements com.agentflow.core.AgentRuntime {
    private final AgentModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final StepRecorder stepRecorder;
    private final ToolResultNormalizer resultNormalizer;

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.stepRecorder = stepRecorder == null ? step -> { } : stepRecorder;
        this.resultNormalizer = resultNormalizer == null ? ToolResultNormalizer.IDENTITY : resultNormalizer;
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder, ToolResultNormalizer.IDENTITY);
    }

    @Override
    public AgentResult run(com.agentflow.core.AgentRequest request, AgentEventSink eventSink,
                           AgentRunOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.defaults() : options;
        RuntimeTrace trace = new RuntimeTrace(request.taskId(), stepRecorder, eventSink);
        UsageAccumulator usage = new UsageAccumulator();
        Set<String> decisionIds = new HashSet<>();
        Set<String> callIds = new HashSet<>();
        long startedAt = System.nanoTime();

        List<ToolDefinition> definitions;
        try {
            definitions = List.copyOf(toolRegistry.enabledDefinitions());
            AgentExecutionContext context = new AgentExecutionContext(request, definitions);
            int iteration = 1;
            while (true) {
                Termination termination = boundary(effectiveOptions, usage, iteration, startedAt);
                if (termination != null) {
                    return finishFailure(request, trace, usage, termination.reason(), termination.status(), termination.diagnostic());
                }

                AgentModelRequest modelRequest = context.modelRequest(iteration);
                long actionStarted = System.nanoTime();
                ModelDecision decision;
                try {
                    decision = modelClient.decide(modelRequest);
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            ex.getMessage(), elapsed(actionStarted), AgentErrorCode.MODEL_ERROR.name(), null, null, false);
                    return finishFailure(request, trace, usage, TerminationReason.MODEL_ERROR,
                            RunStatus.FAILED, "model decision failed");
                }

                if (decision == null) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            "model returned no decision", elapsed(actionStarted),
                            AgentErrorCode.INVALID_DECISION.name(), null, null, false);
                    return finishFailure(request, trace, usage, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "model returned no decision");
                }
                if (!decisionIds.add(decision.decisionId())) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            "duplicate decision id", elapsed(actionStarted),
                            AgentErrorCode.INVALID_DECISION.name(), decision.decisionId(), null, false);
                    return finishFailure(request, trace, usage, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "duplicate decision id");
                }
                try {
                    usage.add(decision.usage());
                } catch (ArithmeticException ex) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(),
                            "token usage overflow", elapsed(actionStarted), AgentErrorCode.MODEL_ERROR.name(),
                            decision.decisionId(), null, false);
                    return finishFailure(request, trace, usage, TerminationReason.MODEL_ERROR,
                            RunStatus.FAILED, "token usage overflow");
                }
                trace.success(AgentStepType.MODEL_DECISION, "model", request.input(),
                        decisionDescription(decision), elapsed(actionStarted), decision.usage(),
                        decision.decisionId(), decision instanceof ToolCallDecision t ? t.toolCall().callId() : null, false);

                if (usage.exceeds(effectiveOptions.budget())) {
                    return finishFailure(request, trace, usage, TerminationReason.BUDGET_EXCEEDED,
                            RunStatus.BUDGET_EXCEEDED, "token budget exceeded");
                }

                if (decision instanceof FinalAnswerDecision finalDecision) {
                    long finalStarted = System.nanoTime();
                    trace.success(AgentStepType.FINAL, "final-answer", request.input(),
                            finalDecision.answer(), elapsed(finalStarted), finalDecision.usage(),
                            finalDecision.decisionId(), null, false);
                    trace.terminate(TerminationReason.COMPLETED, RunStatus.SUCCEEDED, "completed");
                    return AgentResult.success(request.taskId(), finalDecision.answer(), trace.steps(), usage.toTokenUsage());
                }
                if (!(decision instanceof ToolCallDecision toolDecision)) {
                    return finishFailure(request, trace, usage, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "unsupported model decision");
                }

                ToolCall call = toolDecision.toolCall();
                if (!callIds.add(call.callId())) {
                    trace.failure(AgentStepType.TOOL_CALL, call.name(), call.arguments().values().toString(),
                            "duplicate tool call id", 0, AgentErrorCode.INVALID_DECISION.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, usage, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "duplicate tool call id");
                }
                context.confirmToolCall(call);
                trace.success(AgentStepType.TOOL_CALL, call.name(), call.arguments().values().toString(),
                        "requested", 0, null, toolDecision.decisionId(), call.callId(), false);

                ToolLookup lookup;
                try {
                    lookup = toolRegistry.lookup(call.name());
                } catch (RuntimeException ex) {
                    return toolFailure(request, context, trace, usage, call, toolDecision,
                            TerminationReason.UNKNOWN_TOOL, AgentErrorCode.UNKNOWN_TOOL.name(), ex.getMessage());
                }
                if (lookup == null || lookup.availability() == ToolAvailability.UNKNOWN) {
                    return toolFailure(request, context, trace, usage, call, toolDecision,
                            TerminationReason.UNKNOWN_TOOL, AgentErrorCode.UNKNOWN_TOOL.name(), "unknown tool");
                }
                if (lookup.availability() == ToolAvailability.DISABLED) {
                    return toolFailure(request, context, trace, usage, call, toolDecision,
                            TerminationReason.DISABLED_TOOL, AgentErrorCode.DISABLED_TOOL.name(), "tool is disabled");
                }
                ValidationResult validation = lookup.registration().definition().schema().validate(call.arguments());
                if (!validation.valid()) {
                    return toolFailure(request, context, trace, usage, call, toolDecision,
                            TerminationReason.INVALID_TOOL_ARGUMENTS, AgentErrorCode.INVALID_TOOL_ARGUMENTS.name(),
                            validation.violations().toString());
                }

                long toolStarted = System.nanoTime();
                ToolResult rawResult;
                try {
                    rawResult = toolExecutor.execute(call, new com.agentflow.core.tool.ToolContext(
                            request.taskId(), request.sessionId(), request.userId()));
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            ex.getMessage(), elapsed(toolStarted), AgentErrorCode.TOOL_ERROR.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, usage, TerminationReason.TOOL_ERROR,
                            RunStatus.FAILED, "tool execution failed");
                }
                ToolResult normalized;
                try {
                    normalized = canonicalize(call, resultNormalizer.normalize(call, rawResult));
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            ex.getMessage(), elapsed(toolStarted), AgentErrorCode.TOOL_RESULT_INVALID.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, usage, TerminationReason.TOOL_RESULT_INVALID,
                            RunStatus.FAILED, "tool result invalid");
                }
                if (normalized.outputOrEmpty().length() > 8192) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            "tool result exceeds output limit", elapsed(toolStarted),
                            AgentErrorCode.TOOL_RESULT_TOO_LARGE.name(), toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, usage, TerminationReason.TOOL_RESULT_TOO_LARGE,
                            RunStatus.FAILED, "tool result exceeds output limit");
                }
                if (normalized.status() == ToolResultStatus.FAILED) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                            normalized.diagnostic(), elapsed(toolStarted), normalized.errorCode(),
                            toolDecision.decisionId(), call.callId(), false);
                    return finishFailure(request, trace, usage, TerminationReason.TOOL_ERROR,
                            RunStatus.FAILED, normalized.diagnostic());
                }
                trace.success(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                        normalized.outputOrEmpty(), elapsed(toolStarted), null, toolDecision.decisionId(), call.callId(), false);
                context.confirmToolResult(normalized);
                iteration++;
            }
        } catch (RuntimeException ex) {
            trace.failure(AgentStepType.FAILURE, "runtime", request.input(), ex.getMessage(),
                    0, AgentErrorCode.INVALID_INPUT.name(), null, null, false);
            return finishFailure(request, trace, usage, TerminationReason.INVALID_INPUT,
                    RunStatus.FAILED, "runtime input or context invalid");
        }
    }

    private AgentResult toolFailure(AgentRequest request, AgentExecutionContext context, RuntimeTrace trace,
                                    UsageAccumulator usage, ToolCall call, ToolCallDecision decision,
                                    TerminationReason reason, String code, String diagnostic) {
        trace.failure(AgentStepType.TOOL_RESULT, call.name(), call.arguments().values().toString(),
                diagnostic, 0, code, decision.decisionId(), call.callId(), false);
        return finishFailure(request, trace, usage, reason, RunStatus.FAILED, diagnostic);
    }

    private AgentResult finishFailure(AgentRequest request, RuntimeTrace trace, UsageAccumulator usage,
                                      TerminationReason reason, RunStatus status, String diagnostic) {
        trace.terminate(reason, status, diagnostic);
        return AgentResult.failure(request.taskId(), status, reason, diagnostic, trace.steps(), usage.toTokenUsage());
    }

    private static ToolResult canonicalize(ToolCall call, ToolResult result) {
        if (result == null) {
            throw new IllegalArgumentException("tool returned null result");
        }
        if (!call.name().equals(result.toolName())) {
            throw new IllegalArgumentException("tool result name does not match call");
        }
        if (result.status() == ToolResultStatus.SUCCESS
                && (result.output() == null || result.output().isBlank())) {
            throw new IllegalArgumentException("successful tool result must have output");
        }
        if (result.status() == ToolResultStatus.FAILED
                && (result.errorCode() == null || result.errorCode().isBlank())) {
            throw new IllegalArgumentException("failed tool result must have error code");
        }
        return result.callId() == null || result.callId().equals(call.callId())
                ? (result.callId() == null
                    ? new ToolResult(result.toolName(), result.output(), result.status(), result.errorCode(),
                        result.diagnostic(), result.truncated(), call.callId())
                    : result)
                : throwMismatch();
    }

    private static ToolResult throwMismatch() {
        throw new IllegalArgumentException("tool result call id does not match call");
    }

    private static String decisionDescription(ModelDecision decision) {
        return decision instanceof FinalAnswerDecision finalDecision
                ? finalDecision.answer()
                : ((ToolCallDecision) decision).toolCall().name();
    }

    private static long elapsed(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static Termination boundary(AgentRunOptions options, UsageAccumulator usage,
                                       int iteration, long startedAt) {
        if (options.cancellationSignal().isCancelled()) {
            return new Termination(TerminationReason.CANCELLED, RunStatus.CANCELLED, "cancelled");
        }
        Duration maxDuration = options.budget().maxDuration();
        if (maxDuration.isZero() || System.nanoTime() - startedAt >= maxDuration.toNanos()) {
            return new Termination(TerminationReason.TIMED_OUT, RunStatus.TIMED_OUT, "time budget exceeded");
        }
        if (iteration > options.budget().maxIterations() || usage.exceeds(options.budget())) {
            return new Termination(TerminationReason.BUDGET_EXCEEDED, RunStatus.BUDGET_EXCEEDED, "execution budget exceeded");
        }
        return null;
    }

    private record Termination(TerminationReason reason, RunStatus status, String diagnostic) { }

    private static final class UsageAccumulator {
        private long prompt;
        private long completion;
        private long total;
        void add(TokenUsage value) {
            Objects.requireNonNull(value, "usage must not be null");
            prompt = Math.addExact(prompt, value.promptTokens());
            completion = Math.addExact(completion, value.completionTokens());
            total = Math.addExact(total, value.totalTokens());
        }
        boolean exceeds(ExecutionBudget budget) {
            return prompt > budget.maxPromptTokens() || completion > budget.maxCompletionTokens();
        }
        TokenUsage toTokenUsage() {
            return new TokenUsage(Math.toIntExact(prompt), Math.toIntExact(completion), Math.toIntExact(total));
        }
    }
}
