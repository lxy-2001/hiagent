package com.agentflow.core.model;

public interface AgentModelClient {
    ModelDecision decide(AgentModelRequest request);
}
