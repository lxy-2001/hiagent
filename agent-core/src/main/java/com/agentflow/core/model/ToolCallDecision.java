package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.ToolCall;

import java.util.Objects;

public record ToolCallDecision(
        String decisionId,
        ToolCall toolCall,
        TokenUsage usage
) implements ModelDecision {
    public ToolCallDecision {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        if (decisionId.isBlank()) {
            throw new IllegalArgumentException("decisionId must not be blank");
        }
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
    }
}
