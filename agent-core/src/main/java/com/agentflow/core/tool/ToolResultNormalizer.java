package com.agentflow.core.tool;

public interface ToolResultNormalizer {
    ToolResultNormalizer IDENTITY = (call, result) -> result;

    ToolResult normalize(ToolCall call, ToolResult result);

    default ToolResult normalizeManaged(ToolCall call, ToolResult result) {
        return normalize(call, result);
    }
}
