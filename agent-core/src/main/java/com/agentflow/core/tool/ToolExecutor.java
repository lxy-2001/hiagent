package com.agentflow.core.tool;

public interface ToolExecutor {
    ToolResult execute(ToolCall call, ToolContext context);
}
