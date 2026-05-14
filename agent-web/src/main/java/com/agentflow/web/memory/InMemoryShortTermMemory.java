package com.agentflow.web.memory;

import com.agentflow.core.memory.ShortTermMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryShortTermMemory implements ShortTermMemory {

    private final Map<String, List<String>> messages = new ConcurrentHashMap<>();

    @Override
    public void appendUserMessage(String sessionId, String message) {
        append(sessionId, "USER: " + message);
    }

    @Override
    public void appendAssistantMessage(String sessionId, String message) {
        append(sessionId, "ASSISTANT: " + message);
    }

    @Override
    public List<String> recentMessages(String sessionId, int limit) {
        List<String> sessionMessages = messages.getOrDefault(sessionId, List.of());
        int fromIndex = Math.max(0, sessionMessages.size() - Math.max(0, limit));
        return List.copyOf(sessionMessages.subList(fromIndex, sessionMessages.size()));
    }

    private void append(String sessionId, String message) {
        messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).add(message);
    }
}
