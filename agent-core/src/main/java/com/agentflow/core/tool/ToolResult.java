package com.agentflow.core.tool;

public record ToolResult(
        String toolName,
        String output
) {
    @Override
    public String toString() {
        return output;
    }
}
