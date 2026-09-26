package com.agentflow.eval;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentResult;
import com.agentflow.core.context.ContextDiagnostics;
import java.util.List;
import java.util.Map;

/** In-memory evidence only. Never serialize this object: it contains raw runtime content. */
public record ObservedCase(AgentResult result, List<AgentEvent> events,
                           List<ContextDiagnostics> contextDiagnostics,
                           List<EvaluationMetrics.Attempt> attempts,
                           boolean collectorComplete, Integer dispatchCount,
                           Map<EvalCase.Rule, Fact> facts,
                           String webStatus, String webTerminationReason, WebTrace webTrace,
                           Map<String, EvaluationMetrics.Metric> metrics, List<String> supportingRunIds) {
    public ObservedCase(AgentResult result, List<AgentEvent> events, List<ContextDiagnostics> diagnostics,
                        List<EvaluationMetrics.Attempt> attempts, boolean complete, Integer dispatchCount,
                        Map<EvalCase.Rule, Fact> facts, String status, String reason, WebTrace webTrace) {
        this(result, events, diagnostics, attempts, complete, dispatchCount, facts, status, reason, webTrace, Map.of(), List.of());
    }
    public record StepFact(int stepNo, boolean terminal, String type, String status, String toolName, String callId, String errorCode) { }
    public record LifecycleEvent(String runId, long sequence, String type, String status, String reason) { }
    public record WebTrace(String runId, List<StepFact> steps, boolean recordingComplete,
                           List<LifecycleEvent> lifecycle, int modelAttempts) {
        public WebTrace(String runId, List<StepFact> steps, boolean complete) { this(runId, steps, complete, List.of(), -1); }
        public WebTrace { steps = List.copyOf(steps); lifecycle = List.copyOf(lifecycle); }
    }
    public ObservedCase(AgentResult result, List<AgentEvent> events, List<ContextDiagnostics> diagnostics,
                        List<EvaluationMetrics.Attempt> attempts, boolean complete, Integer dispatchCount,
                        Map<EvalCase.Rule, Fact> facts, String status, String reason) {
        this(result, events, diagnostics, attempts, complete, dispatchCount, facts, status, reason, null);
    }
    public ObservedCase {
        events = List.copyOf(events); contextDiagnostics = List.copyOf(contextDiagnostics);
        attempts = List.copyOf(attempts); facts = Map.copyOf(facts);
        metrics = Map.copyOf(metrics); supportingRunIds = List.copyOf(supportingRunIds);
        if (dispatchCount != null && dispatchCount < 0) throw new IllegalArgumentException("Negative dispatch count");
    }
    /** A driver captures actual values; required values come from its fixed fixture, never case expectations. */
    public record Fact(Object actual, Object required) {
        public Fact { actual = freeze(actual); required = freeze(required); }
        private static Object freeze(Object value) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
            if (value instanceof List<?> list) return list.stream().map(Fact::freeze).toList();
            if (value instanceof Map<?, ?> map) {
                var result = new java.util.LinkedHashMap<String, Object>();
                map.forEach((k, v) -> {
                    if (!(k instanceof String key)) throw new IllegalArgumentException("Evidence keys must be strings");
                    result.put(key, freeze(v));
                });
                return java.util.Collections.unmodifiableMap(result);
            }
            throw new IllegalArgumentException("Evidence must contain immutable scalar facts");
        }
    }
}
