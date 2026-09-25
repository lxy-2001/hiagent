package com.agentflow.eval;

import java.util.ArrayList;
import java.util.Objects;
import static com.agentflow.eval.AssertionResult.Status.*;

/** Scores recorded evidence without invoking a model, changing fixtures, or repairing observations. */
public final class EvaluationScorer {
    public CaseReport score(EvalCase definition, ObservedCase observed) {
        var assertions = new ArrayList<AssertionResult>();
        for (var rule : definition.expectations().hardRules()) assertions.add(check(definition, observed, rule, true));
        for (var rule : definition.expectations().taskRules()) assertions.add(check(definition, observed, rule, false));
        CaseReport.Status status = assertions.stream().anyMatch(a -> a.status() == FAIL) ? CaseReport.Status.FAIL
                : assertions.stream().anyMatch(a -> a.status() == INCOMPLETE) ? CaseReport.Status.INCOMPLETE : CaseReport.Status.PASS;
        return new CaseReport(definition.id(), status, assertions);
    }

    private AssertionResult check(EvalCase c, ObservedCase o, EvalCase.Rule rule, boolean hard) {
        Boolean matches = switch (rule) {
            case TERMINAL -> {
                String status = o.result() == null ? o.webStatus() : o.result().status().name();
                String reason = o.result() == null ? o.webTerminationReason() : o.result().terminationReason().name();
                yield status == null || reason == null ? null : status.equals(c.expectations().runStatus().name())
                        && reason.equals(c.expectations().terminationReason());
            }
            case TRACE_COMPLETE -> traceComplete(o) ? true : null;
            case DISPATCH_COUNT -> o.dispatchCount() == null ? null : o.dispatchCount() == c.expectations().dispatchCount();
            case USAGE_UNKNOWN -> o.attempts().isEmpty() ? null : EvaluationMetrics.usage(o.attempts()).status().equals("UNKNOWN");
            default -> {
                var fact = o.facts().get(rule);
                yield fact == null || fact.actual() == null || fact.required() == null ? null
                        : Objects.equals(fact.actual(), fact.required());
            }
        };
        // A broken trace cannot establish a successful trace-dependent assertion.
        if (Boolean.TRUE.equals(matches) && rule != EvalCase.Rule.NO_SECRET && !traceComplete(o)) matches = null;
        var status = matches == null ? INCOMPLETE : matches ? PASS : FAIL;
        return new AssertionResult(rule, hard, status,
                matches == null ? "EVIDENCE_MISSING" : matches ? "EXPECTED" : "MISMATCH", null, null);
    }

    public static boolean traceComplete(ObservedCase observed) {
        if (!observed.collectorComplete() || observed.dispatchCount() == null) return false;
        var result = observed.result();
        String runId;
        java.util.List<ObservedCase.StepFact> steps;
        if (result != null) {
            runId = result.taskId();
            if (result.steps().stream().anyMatch(s -> !s.taskId().equals(runId))) return false;
            steps = result.steps().stream().map(s -> new ObservedCase.StepFact(s.stepNo(), s.terminal(), s.stepType().name(), s.status().name(), s.toolName(), s.callId(), s.errorCode())).toList();
            if (result.toolInvocations().stream().mapToInt(i -> i.dispatchCount()).sum() != observed.dispatchCount()) return false;
        } else {
            var web = observed.webTrace();
            if (web == null || !web.recordingComplete() || observed.dispatchCount() != 0) return false;
            runId = web.runId(); steps = web.steps();
        }
        if (steps.isEmpty()) return false;
        int expected = 1, terminals = 0;
        for (var step : steps) {
            if (step.stepNo() != expected++) return false;
            if (step.terminal()) terminals++;
        }
        if (terminals != 1 || !steps.get(steps.size() - 1).terminal()) return false;
        long sequence = 1;
        int terminalEvents = 0;
        for (var event : observed.events()) {
            if (!event.taskId().equals(runId) || event.sequence() != sequence++) return false;
            if (event.terminal()) terminalEvents++;
        }
        return terminalEvents == 1 && observed.events().get(observed.events().size() - 1).terminal();
    }
}