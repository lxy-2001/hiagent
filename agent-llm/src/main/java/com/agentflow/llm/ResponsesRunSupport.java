package com.agentflow.llm;

import com.agentflow.core.context.HistoryStore;
import com.agentflow.core.model.AgentModelRequest;

/** Application-owned run I/O and durable model-call metadata; no Web types cross this port. */
public interface ResponsesRunSupport {
    HistoryStore.Io io(AgentModelRequest request);
    void record(OpenAiResponsesAgentModelClient.CallReport report);
}
