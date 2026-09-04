package com.agentflow.core.model;

import com.agentflow.core.chat.TokenUsage;

public sealed interface ModelDecision permits FinalAnswerDecision, ToolCallDecision {
    String decisionId();
    TokenUsage usage();
}
