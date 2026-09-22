package com.agentflow.core.context;

import java.util.List;

/** Content-free explanation of a single context selection. */
public record ContextDiagnostics(int iteration, long historyThroughTurn, String promptVersion,
                                 String policyVersion, String estimatorVersion, int keptTurns,
                                 int droppedTurns, int keptMemory, int droppedMemory,
                                 long mandatory, long estimatedInput, int reserve, long window,
                                 boolean candidateLimited, int sourceDiscarded,
                                 List<Selection> selections, String reason) {
    public ContextDiagnostics {
        selections = List.copyOf(selections);
    }

    public record Selection(String sourceId, long sequence, String reason) { }

    public String summary() {
        return "iteration=" + iteration + " prompt=" + promptVersion + " policy=" + policyVersion
                + " estimator=" + estimatorVersion + " through=" + historyThroughTurn
                + " keptTurns=" + keptTurns + " droppedTurns=" + droppedTurns
                + " keptMemory=" + keptMemory + " droppedMemory=" + droppedMemory
                + " mandatory=" + mandatory + " estimatedInput=" + estimatedInput
                + " reserve=" + reserve + " window=" + window + " candidateLimited=" + candidateLimited
                + " sourceDiscarded=" + sourceDiscarded + " reason=" + reason;
    }
}

