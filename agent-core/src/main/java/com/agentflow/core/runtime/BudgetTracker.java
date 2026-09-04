package com.agentflow.core.runtime;

import com.agentflow.core.chat.TokenUsage;

import java.util.Objects;

/** Checked, run-local accumulation of model usage and iteration boundaries. */
public final class BudgetTracker {
    private final ExecutionBudget budget;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private boolean overflowed;

    public BudgetTracker(ExecutionBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    public void record(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        if (overflowed) {
            return;
        }
        try {
            promptTokens = Math.addExact(promptTokens, usage.promptTokens());
            completionTokens = Math.addExact(completionTokens, usage.completionTokens());
            totalTokens = Math.addExact(totalTokens, usage.totalTokens());
        } catch (ArithmeticException ex) {
            overflowed = true;
            promptTokens = Long.MAX_VALUE;
            completionTokens = Long.MAX_VALUE;
            totalTokens = Long.MAX_VALUE;
        }
    }

    public boolean iterationExceeded(int nextIteration) {
        return nextIteration > budget.maxIterations();
    }

    /** Returns true only after a response has exceeded a token allowance. */
    public boolean tokenExceeded() {
        return overflowed || promptTokens > budget.maxPromptTokens()
                || completionTokens > budget.maxCompletionTokens();
    }

    /** Returns true when no further model action may start at the current totals. */
    public boolean tokenBudgetReached() {
        return overflowed || promptTokens >= budget.maxPromptTokens()
                || completionTokens >= budget.maxCompletionTokens();
    }

    public boolean overflowed() {
        return overflowed;
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    /** Remaining completion allowance exposed to the provider adapter. */
    public int remainingCompletionTokens() {
        long remaining = (long) budget.maxCompletionTokens() - completionTokens;
        if (remaining <= 0) {
            return 0;
        }
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    public long totalTokens() {
        return totalTokens;
    }

    public TokenUsage snapshot() {
        return new TokenUsage(toInt(promptTokens), toInt(completionTokens), toInt(totalTokens));
    }

    private static int toInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
