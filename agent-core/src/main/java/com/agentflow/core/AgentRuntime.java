package com.agentflow.core;

import com.agentflow.core.runtime.AgentRunOptions;

public interface AgentRuntime {

    AgentResult run(AgentRequest request, AgentEventSink eventSink, AgentRunOptions options);

    default AgentResult run(AgentRequest request, AgentEventSink eventSink) {
        return run(request, eventSink, AgentRunOptions.defaults());
    }
}
