package com.agentflow.core.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public InMemoryToolRegistry(Collection<AgentTool> tools) {
        tools.forEach(this::register);
    }

    @Override
    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    @Override
    public Optional<AgentTool> findEnabled(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Set<String> enabledToolNames() {
        return Set.copyOf(tools.keySet());
    }
}
