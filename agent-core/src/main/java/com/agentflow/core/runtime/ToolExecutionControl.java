package com.agentflow.core.runtime;

import com.agentflow.core.cancel.CancellationSignal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Cancellation and elapsed-time budget shared across one tool invocation. */
public final class ToolExecutionControl {
    private final CancellationSignal cancellation;
    private final TimeSource clock;
    private final long budgetNanos;
    private final long startedAt;
    private final ToolExecutionControl parent;
    private long lastRemaining;

    public ToolExecutionControl(CancellationSignal cancellation, TimeSource clock, Duration budget) {
        this(cancellation, clock, budget, null);
    }

    private ToolExecutionControl(CancellationSignal cancellation, TimeSource clock, Duration budget,
                                 ToolExecutionControl parent) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative");
        }
        this.budgetNanos = budget.toNanos();
        this.lastRemaining = budgetNanos;
        this.startedAt = clock.nanoTime();
        this.parent = parent;
    }

    public synchronized Duration remainingTime() {
        long elapsed = Math.max(0, clock.nanoTime() - startedAt);
        long remaining = elapsed >= budgetNanos ? 0 : budgetNanos - elapsed;
        if (parent != null) {
            remaining = Math.min(remaining, parent.remainingTime().toNanos());
        }
        lastRemaining = Math.min(lastRemaining, remaining);
        return Duration.ofNanos(lastRemaining);
    }
    public boolean isCancelled() { return cancellation.isCancelled(); }
    public CancellationSignal cancellationSignal() { return cancellation; }
    public void checkActive() {
        if (isCancelled()) {
            throw new CancellationException("tool execution cancelled");
        }
        if (remainingTime().isZero()) {
            throw new ExecutionTimedOutException();
        }
    }
    public ToolExecutionControl child(Duration maxBudget) {
        Objects.requireNonNull(maxBudget, "maxBudget");
        if (maxBudget.isNegative()) {
            throw new IllegalArgumentException("child budget must not be negative");
        }
        Duration remaining = remainingTime();
        return new ToolExecutionControl(cancellation, clock,
                maxBudget.compareTo(remaining) < 0 ? maxBudget : remaining, this);
    }

    public static final class ExecutionTimedOutException extends RuntimeException {
        public ExecutionTimedOutException() { super("tool execution timed out"); }
    }
}
