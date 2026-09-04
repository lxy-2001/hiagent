package com.agentflow.core.tool;

import com.agentflow.core.rag.RagDocument;

import java.util.List;

public record ToolContext(
        String taskId,
        String sessionId,
        String userId,
        List<RagDocument> knowledge
) {
    public ToolContext {
        if (taskId == null || taskId.isBlank() || sessionId == null || sessionId.isBlank()
                || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tool context identifiers must be non-blank");
        }
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public ToolContext(String taskId, String sessionId, String userId) {
        this(taskId, sessionId, userId, List.of());
    }
}
