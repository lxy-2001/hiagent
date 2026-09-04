package com.agentflow.core.model;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable input supplied to one model decision. */
public record AgentModelRequest(
        String taskId,
        String sessionId,
        String userId,
        String input,
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        int iteration,
        Integer maxCompletionTokens
) {
    public AgentModelRequest {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(userId, "userId");
        requireNonBlank(input, "input");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (maxCompletionTokens != null && maxCompletionTokens < 0) {
            throw new IllegalArgumentException("maxCompletionTokens must not be negative");
        }
        messages = Collections.unmodifiableList(new ArrayList<>(messages));
        tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public AgentModelRequest(String taskId, String sessionId, String userId, String input,
                             List<ModelMessage> messages, List<ToolDefinition> tools, int iteration) {
        this(taskId, sessionId, userId, input, messages, tools, iteration, null);
    }

    public AgentModelRequest(AgentRequest request, List<ModelMessage> messages,
                             List<ToolDefinition> tools, int iteration) {
        this(request, messages, tools, iteration, null);
    }

    public AgentModelRequest(AgentRequest request, List<ModelMessage> messages,
                             List<ToolDefinition> tools, int iteration, Integer maxCompletionTokens) {
        this(request.taskId(), request.sessionId(), request.userId(), request.input(),
                messages, tools, iteration, maxCompletionTokens);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
