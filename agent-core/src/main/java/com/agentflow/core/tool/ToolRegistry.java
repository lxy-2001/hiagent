package com.agentflow.core.tool;

import java.util.Optional;
import java.util.Set;

public interface ToolRegistry {

    void register(AgentTool tool);

    Optional<AgentTool> findEnabled(String name);

    Set<String> enabledToolNames();
}
