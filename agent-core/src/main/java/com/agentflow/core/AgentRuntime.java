package com.agentflow.core;

public interface AgentRuntime {

    AgentResult run(AgentRequest request, AgentEventSink eventSink);
}
