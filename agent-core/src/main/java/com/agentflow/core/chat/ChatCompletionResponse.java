package com.agentflow.core.chat;

public record ChatCompletionResponse(
        String provider,
        String model,
        String content,
        TokenUsage usage,
        boolean mocked
) {
}
