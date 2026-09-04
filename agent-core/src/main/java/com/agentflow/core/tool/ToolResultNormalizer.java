package com.agentflow.core.tool;

public interface ToolResultNormalizer {
    ToolResultNormalizer IDENTITY = (call, result) -> result;

    ToolResult normalize(ToolCall call, ToolResult result);
}
