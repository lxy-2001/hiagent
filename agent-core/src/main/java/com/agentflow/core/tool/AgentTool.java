package com.agentflow.core.tool;

public interface AgentTool {
    ToolDefinition definition();
    ToolResult execute(ToolArguments arguments, ToolContext context);
}
