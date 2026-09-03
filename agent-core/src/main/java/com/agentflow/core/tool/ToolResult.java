package com.agentflow.core.tool;

import java.util.Objects;

public record ToolResult(
        String toolName,
        String output,
        ToolResultStatus status,
        String errorCode,
        String diagnostic,
        boolean truncated,
        String callId
) {
    public ToolResult {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must be non-blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (status == ToolResultStatus.SUCCESS && (output == null || output.isBlank())) {
            throw new IllegalArgumentException("successful tool result must have output");
        }
        if (status == ToolResultStatus.FAILED && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("failed tool result must have errorCode");
        }
        if (output != null && output.length() > ToolArguments.MAX_STRING_CHARS * 2) {
            throw new IllegalArgumentException("tool result output exceeds hard limit");
        }
    }

    public ToolResult(String toolName, String output) {
        this(toolName, output, ToolResultStatus.SUCCESS, null, null, false, null);
    }

    public static ToolResult success(String toolName, String output) {
        return new ToolResult(toolName, output, ToolResultStatus.SUCCESS, null, null, false, null);
    }

    public static ToolResult failure(String toolName, String callId, String errorCode, String diagnostic) {
        return new ToolResult(toolName, null, ToolResultStatus.FAILED, errorCode, diagnostic, false, callId);
    }

    public String outputOrEmpty() {
        return output == null ? "" : output;
    }
}
