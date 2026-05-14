package com.agentflow.web.chat;

import com.agentflow.core.chat.TokenUsage;

public record ChatResponse(
        String sessionId,
        String provider,
        String model,
        String content,
        TokenUsage usage,
        boolean mocked
) {
}
