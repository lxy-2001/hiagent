package com.agentflow.core.runtime;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.ContextAssembler;
import com.agentflow.core.context.ContextAssembly;
import com.agentflow.core.context.ContextPolicy;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.Utf8TokenEstimator;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.DefaultToolExecutor;
import com.agentflow.core.tool.DefaultToolResultNormalizer;
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
    private static final ToolResultNormalizer SAFETY_NORMALIZER = new DefaultToolResultNormalizer();
    private final AgentModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final StepRecorder stepRecorder;
    private final ToolResultNormalizer resultNormalizer;
    private final TimeSource timeSource;
    private final ContextAssembler contextAssembler;

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer, TimeSource timeSource) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder, resultNormalizer, timeSource,
                new ContextAssembler(ContextPolicy.defaults(), new Utf8TokenEstimator(), new ContextTextPolicy()));
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer, TimeSource timeSource,
                               ContextAssembler contextAssembler) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.stepRecorder = stepRecorder == null ? step -> { } : stepRecorder;
        this.resultNormalizer = resultNormalizer == null ? new com.agentflow.core.tool.DefaultToolResultNormalizer() : resultNormalizer;
        this.timeSource = timeSource == null ? TimeSource.system() : timeSource;
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder,
                               ToolResultNormalizer resultNormalizer) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder, resultNormalizer, TimeSource.system());
    }

    public DefaultAgentRuntime(AgentModelClient modelClient, ToolRegistry toolRegistry,
                               ToolExecutor toolExecutor, StepRecorder stepRecorder) {
        this(modelClient, toolRegistry, toolExecutor, stepRecorder,
                new com.agentflow.core.tool.DefaultToolResultNormalizer(), TimeSource.system());
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
        var toolControl = new ToolExecutionControl(effectiveOptions.cancellationSignal(), timeSource, effectiveOptions.budget().maxDuration());

        try {
            List<ToolDefinition> definitions = List.copyOf(
                    Objects.requireNonNull(toolRegistry.enabledDefinitions(), "enabled definitions must not be null"));
            AgentExecutionContext context = new AgentExecutionContext(request, definitions);

            for (int iteration = 1; ; iteration++) {
                Termination boundary = boundary(effectiveOptions, budget, iteration, startedAt);
                if (boundary != null) {
                    return finishFailure(request, trace, budget, boundary.reason(), boundary.status(), boundary.diagnostic());
                }

                ContextAssembly assembly = contextAssembler.assemble(request, context.messages(), definitions,
                        iteration, budget.remainingCompletionTokens());
                if (assembly.ready()) {
                    trace.success(AgentStepType.CONTEXT_ASSEMBLY, "context", null, assembly.diagnostics().summary(),
                            0, null, null, null, false);
                } else {
                    trace.failure(AgentStepType.CONTEXT_ASSEMBLY, "context", null, assembly.diagnostics().summary(),
                            0, assembly.rejectionReason(), null, null, false);
                }
                boundary = boundary(effectiveOptions, budget, iteration, startedAt);
                if (boundary != null) {
                    return finishFailure(request, trace, budget, boundary.reason(), boundary.status(), boundary.diagnostic());
                }
                if (!assembly.ready()) {
                    TerminationReason reason = TerminationReason.valueOf(assembly.rejectionReason());
                    RunStatus status = reason == TerminationReason.CONTEXT_BUDGET_EXCEEDED
                            ? RunStatus.BUDGET_EXCEEDED : RunStatus.FAILED;
                    return finishFailure(request, trace, budget, reason, status, "context assembly rejected");
                }
                AgentModelRequest modelRequest = assembly.request();
                Set<String> eligibleEvidence = context.evidence().eligibleFor(modelRequest.messages());
                long actionStarted = timeSource.nanoTime();
                ModelDecision decision;
                try {
                    decision = modelClient.decide(modelRequest);
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.MODEL_DECISION, "model", request.input(), "model decision failed",
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
                    String answer;
                    try {
                        answer = new ContextTextPolicy().sanitizeInput(finalDecision.answer());
                        if (answer.length() > 65536) { throw new IllegalArgumentException("answer too large"); }
                    } catch (IllegalArgumentException invalid) {
                        return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION, RunStatus.FAILED, "invalid final answer");
                    }
                    var validation = new com.agentflow.core.rag.CitationValidator().validate(answer, request.requireEvidence(), eligibleEvidence, context.evidence());
                    boolean needsValidationTrace = request.requireEvidence() || answer.contains("[S") || !eligibleEvidence.isEmpty();
                    if (!validation.valid()) {
                        trace.failure(AgentStepType.CITATION_VALIDATION, "citations", null, "citation validation rejected",
                                0, validation.errorCode(), finalDecision.decisionId(), null, false);
                    } else if (needsValidationTrace) {
                        trace.success(AgentStepType.CITATION_VALIDATION, "citations", null, "citations=" + validation.citations().size(),
                                0, null, finalDecision.decisionId(), null, false);
                    }
                    Termination afterValidation = afterDecisionBoundary(effectiveOptions, budget, startedAt);
                    if (afterValidation != null) {
                        return finishFailure(request, trace, budget, afterValidation.reason(), afterValidation.status(), afterValidation.diagnostic());
                    }
                    if (!validation.valid()) {
                        return finishFailure(request, trace, budget, TerminationReason.valueOf(validation.errorCode()), RunStatus.FAILED,
                                "citation validation rejected");
                    }
                    trace.success(AgentStepType.FINAL, "final-answer", request.input(),
                            answer, 0, finalDecision.usage(),
                            finalDecision.decisionId(), null, false);
                    trace.terminate(TerminationReason.COMPLETED, RunStatus.SUCCEEDED, "completed");
                    return new AgentResult(request.taskId(), answer, trace.steps(), RunStatus.SUCCEEDED,
                            TerminationReason.COMPLETED, budget.snapshot(), "", validation.citations());
                }
                if (!(decision instanceof ToolCallDecision toolDecision)) {
                    return finishFailure(request, trace, budget, TerminationReason.INVALID_DECISION,
                            RunStatus.FAILED, "unsupported model decision");
                }

                ToolCall call = toolDecision.toolCall();
                if (!callIds.add(call.callId())) {
                    trace.failure(AgentStepType.TOOL_CALL, call.name(), toolInput(call),
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
                trace.success(AgentStepType.TOOL_CALL, call.name(), toolInput(call),
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
                            new com.agentflow.core.tool.ToolContext(request.taskId(), request.sessionId(), request.userId(), List.of(), toolControl));
                } catch (RuntimeException ex) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                            ex.getMessage(), elapsed(toolStarted), AgentErrorCode.TOOL_ERROR.name(),
                            toolDecision.decisionId(), call.callId(), false);
                    Termination afterTool = afterToolBoundary(effectiveOptions, startedAt);
                    if (afterTool != null) {
                        return finishFailure(request, trace, budget, afterTool.reason(),
                                afterTool.status(), afterTool.diagnostic());
                    }
                    return finishFailure(request, trace, budget, TerminationReason.TOOL_ERROR,
                            RunStatus.FAILED, "tool execution failed");
                }

                ToolResult normalized;
                String retrievalSummary = null;
                try {
                    normalized = canonicalize(validatedCall, resultNormalizer.normalize(validatedCall, rawResult));
                    if (rawResult != null && rawResult.retrievalPayload() != null && normalized.status() == ToolResultStatus.SUCCESS
                            && !rawResult.retrievalPayload().equals(normalized.retrievalPayload())) {
                        throw new IllegalArgumentException("retrieval evidence changed");
                    }
                    if (normalized.status() == ToolResultStatus.SUCCESS && normalized.retrievalPayload() != null) {
                        var bound = context.evidence().bind(call.callId(), normalized.retrievalPayload());
                        retrievalSummary = bound.summary();
                        normalized = canonicalize(validatedCall, new ToolResult(normalized.toolName(), bound.output(), normalized.status(),
                                null, bound.summary(), false, call.callId(), normalized.retrievalPayload()));
                    }
                } catch (RuntimeException ex) {
                    boolean invalidSource = "knowledge.search".equals(call.name())
                            && "RAG_SOURCE_INVALID".equals(ex.getMessage());
                    String code = invalidSource ? "RAG_SOURCE_INVALID" : AgentErrorCode.TOOL_RESULT_INVALID.name();
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                            ex.getMessage(), elapsed(toolStarted), code,
                            toolDecision.decisionId(), call.callId(), false);
                    Termination afterTool = afterToolBoundary(effectiveOptions, startedAt);
                    if (afterTool != null) {
                        return finishFailure(request, trace, budget, afterTool.reason(),
                                afterTool.status(), afterTool.diagnostic());
                    }
                    return finishFailure(request, trace, budget, terminationReasonFor(code),
                            RunStatus.FAILED, invalidSource ? "retrieval source invalid" : "tool result invalid");
                }
                if (normalized.outputOrEmpty().length() > 8192) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                            "tool result exceeds output limit", elapsed(toolStarted),
                            AgentErrorCode.TOOL_RESULT_TOO_LARGE.name(), toolDecision.decisionId(), call.callId(), false);
                    Termination afterTool = afterToolBoundary(effectiveOptions, startedAt);
                    if (afterTool != null) {
                        return finishFailure(request, trace, budget, afterTool.reason(),
                                afterTool.status(), afterTool.diagnostic());
                    }
                    return finishFailure(request, trace, budget, TerminationReason.TOOL_RESULT_TOO_LARGE,
                            RunStatus.FAILED, "tool result exceeds output limit");
                }
                boolean failedToolResult = normalized.status() == ToolResultStatus.FAILED;
                if (failedToolResult) {
                    trace.failure(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                            "knowledge.search".equals(call.name()) ? "retrieval failed" : normalized.diagnostic(), elapsed(toolStarted), normalized.errorCode(),
                            toolDecision.decisionId(), call.callId(), false);
                } else {
                    trace.success(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                            retrievalSummary == null ? normalized.outputOrEmpty() : retrievalSummary,
                            elapsed(toolStarted), null, toolDecision.decisionId(), call.callId(), false);
                    context.confirmToolResult(normalized);
                }
                Termination afterTool = afterToolBoundary(effectiveOptions, startedAt);
                if (afterTool != null) {
                    return finishFailure(request, trace, budget, afterTool.reason(),
                            afterTool.status(), afterTool.diagnostic());
                }
                if (failedToolResult) {
                    return finishFailure(request, trace, budget, terminationReasonFor(normalized.errorCode()),
                            RunStatus.FAILED, "knowledge.search".equals(call.name()) ? "retrieval failed" : normalized.diagnostic());
                }
            }
        } catch (RuntimeException ex) {
            trace.failure(AgentStepType.FAILURE, "runtime", request.input(), "runtime input or context invalid",
                    0, AgentErrorCode.INVALID_INPUT.name(), null, null, false);
            return finishFailure(request, trace, budget, TerminationReason.INVALID_INPUT,
                    RunStatus.FAILED, "runtime input or context invalid");
        }
    }

    private AgentResult toolFailure(AgentRequest request, RuntimeTrace trace, BudgetTracker budget,
                                    ToolCall call, ToolCallDecision decision, TerminationReason reason,
                                    String code, String diagnostic) {
        if ("knowledge.search".equals(call.name())) { diagnostic = "retrieval failed"; }
        trace.failure(AgentStepType.TOOL_RESULT, call.name(), toolInput(call),
                diagnostic, 0, code, decision.decisionId(), call.callId(), false);
        return finishFailure(request, trace, budget, reason, RunStatus.FAILED, diagnostic);
    }

    private AgentResult finishFailure(AgentRequest request, RuntimeTrace trace, BudgetTracker budget,
                                      TerminationReason reason, RunStatus status, String diagnostic) {
        trace.terminate(reason, status, diagnostic);
        return AgentResult.failure(request.taskId(), status, reason, diagnostic, trace.steps(), budget.snapshot());
    }

    private static ToolResult canonicalize(ToolCall call, ToolResult result) {
        // The Runtime owns the final trust boundary; a custom normalizer must not be
        // able to bypass call correlation, output bounds, or credential redaction.
        ToolResult safe = SAFETY_NORMALIZER.normalize(call, result);
        if (safe.status() == ToolResultStatus.SUCCESS
                && (safe.output() == null || safe.output().isBlank())) {
            throw new IllegalArgumentException("successful tool result must have output");
        }
        if (safe.status() == ToolResultStatus.FAILED
                && (safe.errorCode() == null || safe.errorCode().isBlank())) {
            throw new IllegalArgumentException("failed tool result must have error code");
        }
        return safe;
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

    private static String toolInput(ToolCall call) {
        if (!"knowledge.search".equals(call.name())) { return call.arguments().values().toString(); }
        Object query = call.arguments().values().get("query");
        Object topK = call.arguments().values().get("topK");
        return "queryLength=" + (query instanceof String text ? text.length() : 0)
                + " topK=" + (topK instanceof Number number ? number.longValue() : 5);
    }

    private static String decisionDescription(ModelDecision decision) {
        return decision instanceof FinalAnswerDecision finalDecision
                ? "type=FINAL answerLength=" + finalDecision.answer().length()
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

    /** Checks only cancellation and wall-clock timeout after a Tool boundary. */
    private Termination afterToolBoundary(AgentRunOptions options, long startedAt) {
        return timeBoundary(options, startedAt);
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
