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
        String errorMessage,
        String decisionId,
        String callId,
        String errorCode,
        boolean terminal
) {
    public AgentStepRecord {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must be non-blank");
        }
        if (stepNo < 1) {
            throw new IllegalArgumentException("stepNo must be at least 1");
        }
        if (stepType == null || status == null) {
            throw new NullPointerException("stepType and status must not be null");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
        if ((promptTokens != null && promptTokens < 0)
                || (completionTokens != null && completionTokens < 0)) {
            throw new IllegalArgumentException("step token counts must not be negative");
        }
    }

    public AgentStepRecord(String taskId, int stepNo, AgentStepType stepType, String toolName,
                           String input, String output, AgentStepStatus status, long latencyMs,
                           Integer promptTokens, Integer completionTokens, String errorMessage) {
        this(taskId, stepNo, stepType, toolName, input, output, status, latencyMs,
                promptTokens, completionTokens, errorMessage, null, null, null, false);
    }

    public static AgentStepRecord success(
            String taskId, int stepNo, AgentStepType stepType, String toolName,
            String input, String output, long latencyMs) {
        return new AgentStepRecord(taskId, stepNo, stepType, toolName, input, output,
                AgentStepStatus.SUCCESS, latencyMs, null, null, null);
    }

    public static AgentStepRecord success(
            String taskId, int stepNo, AgentStepType stepType, String name,
            String input, String output, long latencyMs, Integer promptTokens,
            Integer completionTokens, String decisionId, String callId, boolean terminal) {
        return new AgentStepRecord(taskId, stepNo, stepType, name, input, output,
                AgentStepStatus.SUCCESS, latencyMs, promptTokens, completionTokens, null,
                decisionId, callId, null, terminal);
    }

    public static AgentStepRecord failed(
            String taskId, int stepNo, AgentStepType stepType, String toolName,
            String input, String errorMessage, long latencyMs) {
        return new AgentStepRecord(taskId, stepNo, stepType, toolName, input, null,
                AgentStepStatus.FAILED, latencyMs, null, null, errorMessage);
    }

    public static AgentStepRecord failed(
            String taskId, int stepNo, AgentStepType stepType, String name,
            String input, String errorMessage, long latencyMs, String errorCode,
            String decisionId, String callId, boolean terminal) {
        return new AgentStepRecord(taskId, stepNo, stepType, name, input, null,
                AgentStepStatus.FAILED, latencyMs, null, null, errorMessage,
                decisionId, callId, errorCode, terminal);
    }
}
