package com.agentflow.core.memory;

import java.util.List;

public interface ShortTermMemory {

    void appendUserMessage(String sessionId, String message);

    void appendAssistantMessage(String sessionId, String message);

    List<String> recentMessages(String sessionId, int limit);
}
