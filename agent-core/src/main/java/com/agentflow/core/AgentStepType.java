package com.agentflow.core;

public enum AgentStepType {
    PLANNER,
    MEMORY,
    RAG,
    LLM,
    MODEL_DECISION,
    TOOL,
    TOOL_CALL,
    TOOL_RESULT,
    HUMAN_APPROVAL,
    FINAL,
    FAILURE,
    TERMINATION
}
