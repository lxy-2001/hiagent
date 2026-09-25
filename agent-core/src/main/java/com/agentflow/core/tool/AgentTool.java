package com.agentflow.core.tool;

public interface AgentTool {
    ToolDefinition definition();
    /** Trusted adapter source identity, snapshotted when the tool is registered. */
    default String definitionVersion() { return ToolArgumentDigest.definitionVersion(definition()); }
    ToolResult execute(ToolArguments arguments, ToolContext context);
}
