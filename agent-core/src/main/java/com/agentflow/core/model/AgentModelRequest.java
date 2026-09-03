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
        int iteration
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
        messages = Collections.unmodifiableList(new ArrayList<>(messages));
        tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public AgentModelRequest(AgentRequest request, List<ModelMessage> messages,
                             List<ToolDefinition> tools, int iteration) {
        this(request.taskId(), request.sessionId(), request.userId(), request.input(),
                messages, tools, iteration);
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
