package com.agentflow.core.tool;

import java.util.Objects;

public record ToolRegistration(AgentTool tool, boolean enabled) {
    public ToolRegistration {
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(tool.definition(), "tool definition must not be null");
    }

    public ToolDefinition definition() {
        return tool.definition();
    }
}
