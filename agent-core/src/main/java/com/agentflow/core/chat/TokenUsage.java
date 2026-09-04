package com.agentflow.core.chat;

public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }
}
