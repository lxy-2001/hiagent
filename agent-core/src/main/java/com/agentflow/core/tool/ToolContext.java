package com.agentflow.core.tool;

import com.agentflow.core.rag.RagDocument;

import java.util.List;

public record ToolContext(
        String taskId,
        String sessionId,
        String userId,
        List<RagDocument> knowledge
) {
}
