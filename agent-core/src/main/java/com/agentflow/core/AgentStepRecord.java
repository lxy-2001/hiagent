package com.agentflow.core;

public record AgentStepRecord(
        String taskId,
        int stepNo,
        AgentStepType stepType,
        String toolName,
        String input,
        String output,
        AgentStepStatus status,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String errorMessage
) {
    public static AgentStepRecord success(
            String taskId,
            int stepNo,
            AgentStepType stepType,
            String toolName,
            String input,
            String output,
            long latencyMs
    ) {
        return new AgentStepRecord(taskId, stepNo, stepType, toolName, input, output,
                AgentStepStatus.SUCCESS, latencyMs, null, null, null);
    }

    public static AgentStepRecord failed(
            String taskId,
            int stepNo,
            AgentStepType stepType,
            String toolName,
            String input,
            String errorMessage,
            long latencyMs
    ) {
        return new AgentStepRecord(taskId, stepNo, stepType, toolName, input, null,
                AgentStepStatus.FAILED, latencyMs, null, null, errorMessage);
    }
}
