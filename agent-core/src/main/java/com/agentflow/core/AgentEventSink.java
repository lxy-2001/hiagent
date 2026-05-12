package com.agentflow.core;

public interface AgentEventSink {

    AgentEventSink NOOP = event -> {
    };

    void publish(AgentEvent event);
}
