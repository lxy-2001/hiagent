package com.agentflow.demo.evaluation;

import com.agentflow.core.model.*;
import com.agentflow.eval.EvaluationMetrics;
import java.util.ArrayList;
import java.util.List;

final class ObservedModelClient implements AgentModelClient {
    private final AgentModelClient delegate;
    private final List<EvaluationMetrics.Attempt> attempts = new ArrayList<>();
    private final List<AgentModelRequest> requests = new ArrayList<>();
    ObservedModelClient(AgentModelClient delegate) { this.delegate = delegate; }
    @Override public ModelDecision decide(AgentModelRequest request) {
        long start = System.nanoTime();
        ModelDecision decision = null;
        requests.add(request);
        try { decision = delegate.decide(request); return decision; }
        finally {
            attempts.add(new EvaluationMetrics.Attempt(decision == null ? null : decision.usage(),
                    decision == null ? UsageSource.UNKNOWN : decision.usageSource(), (System.nanoTime() - start) / 1000000.0));
        }
    }
    List<EvaluationMetrics.Attempt> attempts(String runId) {
        var result = new ArrayList<EvaluationMetrics.Attempt>();
        for (int i = 0; i < attempts.size(); i++) if (requests.get(i).taskId().equals(runId)) result.add(attempts.get(i));
        return List.copyOf(result);
    }
    List<EvaluationMetrics.Attempt> attempts() { return List.copyOf(attempts); }
    List<AgentModelRequest> requests() { return java.util.Collections.unmodifiableList(new ArrayList<>(requests)); }
}
