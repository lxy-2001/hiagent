package com.agentflow.core.support;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Test-only scripted model. Captures every attempted decision, including unexpected calls. */
public final class ContextModelFixture implements AgentModelClient {
    private final List<ModelDecision> script;
    private final List<AgentModelRequest> requests = new ArrayList<>();

    public ContextModelFixture(List<ModelDecision> script) {
        this.script = List.copyOf(script);
    }

    @Override
    public synchronized ModelDecision decide(AgentModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        int index = requests.size();
        requests.add(request);
        if (index >= script.size()) {
            throw new AssertionError("model fixture script exhausted at call " + (index + 1));
        }
        return script.get(index);
    }

    public synchronized List<AgentModelRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized int callCount() {
        return requests.size();
    }
}
