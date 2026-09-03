package com.agentflow.core.runtime;

import com.agentflow.core.cancel.CancellationSignal;

import java.util.Objects;

public record AgentRunOptions(
        ExecutionBudget budget,
        CancellationSignal cancellationSignal
) {
    public AgentRunOptions {
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    }

    public static AgentRunOptions defaults() {
        return new AgentRunOptions(ExecutionBudget.defaults(), CancellationSignal.NONE);
    }
}
