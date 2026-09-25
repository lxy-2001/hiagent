package com.agentflow.eval;

import com.agentflow.core.runtime.ExecutionBudget;
import com.agentflow.core.runtime.RunStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record EvalCase(String id, Driver driver, String fixtureId, String input,
                       ExecutionBudget budget, Expectations expectations) {
    public enum Driver { CORE, HTTP }
    public enum Rule {
        TERMINAL, TOOL_SEQUENCE, ARGUMENTS_MATCH, DISPATCH_COUNT, WRITE_COUNT,
        CITATIONS_BOUND, CONTEXT_SELECTION, MEMORY_CURRENT, BUDGET_LIMIT,
        OWNER_ISOLATION, NO_SECRET, TRACE_COMPLETE, USAGE_UNKNOWN, HTTP_CAPACITY, ANSWER_MARKER
    }
    public EvalCase {
        Objects.requireNonNull(id); Objects.requireNonNull(driver); Objects.requireNonNull(fixtureId);
        Objects.requireNonNull(input); Objects.requireNonNull(budget); Objects.requireNonNull(expectations);
        if (!id.matches("C[0-9]{2}") || input.isEmpty() || input.length() > 8192
                || budget.maxIterations() > 16 || budget.maxDuration().toMillis() > 15000
                || budget.maxPromptTokens() > 16384 || budget.maxCompletionTokens() > 16384) {
            throw new IllegalArgumentException("Invalid evaluation case");
        }
    }
    public record Expectations(RunStatus runStatus, String terminationReason, int dispatchCount,
                               List<Rule> hardRules, List<Rule> taskRules) {
        public Expectations {
            Objects.requireNonNull(runStatus); Objects.requireNonNull(terminationReason);
            hardRules = List.copyOf(hardRules); taskRules = List.copyOf(taskRules);
            var all = new HashSet<>(hardRules); all.addAll(taskRules);
            if (dispatchCount < 0 || all.size() != hardRules.size() + taskRules.size()
                    || !hardRules.containsAll(List.of(Rule.TERMINAL, Rule.TRACE_COMPLETE))
                    || taskRules.stream().anyMatch(r -> r != Rule.ANSWER_MARKER)
                    || runStatus == RunStatus.SUCCEEDED && !taskRules.contains(Rule.ANSWER_MARKER)) {
                throw new IllegalArgumentException("Invalid evaluation expectations");
            }
        }
    }
}
