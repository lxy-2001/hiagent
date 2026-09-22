package com.agentflow.core.tool;

import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.cancel.CancellationSignal;
import java.time.Duration;
import java.util.Objects;

import java.util.List;

public record ToolContext(
        String taskId,
        String sessionId,
        String userId,
        List<RagDocument> knowledge,
        ToolExecutionControl control
) {
    public ToolContext {
        Objects.requireNonNull(control, "control");
        if (taskId == null || taskId.isBlank() || sessionId == null || sessionId.isBlank()
                || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tool context identifiers must be non-blank");
        }
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
    }

    public ToolContext(String taskId, String sessionId, String userId) {
        this(taskId, sessionId, userId, List.of());
    }

    public ToolContext(String taskId, String sessionId, String userId, List<RagDocument> knowledge) {
        this(taskId, sessionId, userId, knowledge,
                new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofSeconds(30)));
    }
}
