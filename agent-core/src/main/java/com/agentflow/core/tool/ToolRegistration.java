package com.agentflow.core.tool;

import java.util.Objects;

/**
 * Immutable registration snapshot coupling a Tool implementation to the definition
 * that was validated when it entered a registry.
 */
public record ToolRegistration(AgentTool tool, ToolDefinition definition, boolean enabled) {
    /** Compatibility constructor that snapshots the implementation definition. */
    public ToolRegistration(AgentTool tool, boolean enabled) {
        this(tool, snapshot(tool), enabled);
    }

    public ToolRegistration {
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(definition, "tool definition must not be null");
        ToolDefinition implementationDefinition =
                Objects.requireNonNull(tool.definition(), "tool definition must not be null");
        if (!definition.equals(implementationDefinition)) {
            throw new IllegalArgumentException("tool definition is inconsistent");
        }
    }

    private static ToolDefinition snapshot(AgentTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        return Objects.requireNonNull(tool.definition(), "tool definition must not be null");
    }
}
