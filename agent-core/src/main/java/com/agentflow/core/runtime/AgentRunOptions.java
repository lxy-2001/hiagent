package com.agentflow.core.runtime;

import com.agentflow.core.cancel.CancellationSignal;

import java.util.Objects;

public record AgentRunOptions(
        ExecutionBudget budget,
        CancellationSignal cancellationSignal,
        com.agentflow.core.approval.ApprovalGate approvalGate,
        java.time.Duration approvalTtl
) {
    public AgentRunOptions {
        Objects.requireNonNull(approvalTtl,"approvalTtl");
        if (approvalTtl.isZero() || approvalTtl.isNegative() || approvalTtl.compareTo(java.time.Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("approval TTL must be in (0,120s]");
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(cancellationSignal, "cancellationSignal must not be null");
    }

    public AgentRunOptions(ExecutionBudget budget,CancellationSignal signal,com.agentflow.core.approval.ApprovalGate gate) {
        this(budget,signal,gate,java.time.Duration.ofSeconds(30));
    }
    public AgentRunOptions(ExecutionBudget budget, CancellationSignal cancellationSignal) {
        this(budget, cancellationSignal, null);
    }

    public static AgentRunOptions defaults() {
        return new AgentRunOptions(ExecutionBudget.defaults(), CancellationSignal.NONE);
    }
}
