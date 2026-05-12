package com.agentflow.core;

import java.util.List;

public record AgentResult(
        String taskId,
        String finalAnswer,
        List<AgentStepRecord> steps
) {
}
