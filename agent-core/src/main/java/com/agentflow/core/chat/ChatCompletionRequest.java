package com.agentflow.core.chat;

import java.util.List;

public record ChatCompletionRequest(
        List<ChatMessage> messages,
        String model,
        Double temperature,
        Integer maxTokens
) {
    public ChatCompletionRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
    }
}
