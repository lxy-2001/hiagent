package com.agentflow.core.runtime;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Mutable run-local context whose exposed snapshots are immutable. */
public final class AgentExecutionContext {
    private final AgentRequest request;
    private final List<ToolDefinition> toolDefinitions;
    private final List<ModelMessage> messages = new ArrayList<>();
    private final com.agentflow.core.rag.EvidenceLedger evidence = new com.agentflow.core.rag.EvidenceLedger();

    public com.agentflow.core.rag.EvidenceLedger evidence() { return evidence; }

    public AgentExecutionContext(AgentRequest request, List<ToolDefinition> toolDefinitions) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(toolDefinitions, "toolDefinitions must not be null");
        this.toolDefinitions = Collections.unmodifiableList(new ArrayList<>(toolDefinitions));
        this.messages.add(ModelMessage.user(request.input()));
    }

    public AgentRequest request() {
        return request;
    }

    public List<ToolDefinition> toolDefinitions() {
        return toolDefinitions;
    }

    public List<ModelMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public AgentModelRequest modelRequest(int iteration) {
        return modelRequest(iteration, null);
    }

    public AgentModelRequest modelRequest(int iteration, Integer maxCompletionTokens) {
        return new AgentModelRequest(request, messages(), toolDefinitions, iteration, maxCompletionTokens);
    }

    public void confirmToolCall(ToolCall call) {
        Objects.requireNonNull(call, "call must not be null");
        messages.add(ModelMessage.assistantToolCall(call));
    }

    public void confirmToolResult(ToolResult result) {
        Objects.requireNonNull(result, "result must not be null");
        messages.add(ModelMessage.toolResult(result));
    }
}
