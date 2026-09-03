package com.agentflow.core.tool;

public interface ToolResultNormalizer {
    ToolResult normalize(ToolCall call, ToolResult result);
}
