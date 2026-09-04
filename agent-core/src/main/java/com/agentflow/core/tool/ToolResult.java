package com.agentflow.core.tool;

import java.util.Objects;

/** Raw or normalized result envelope returned by a Tool boundary. */
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
        if (toolName == null || toolName.isBlank() || !toolName.equals(toolName.strip())) {
            throw new IllegalArgumentException("toolName must be non-blank and trimmed");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (errorCode != null && errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must be non-blank when present");
        }
        if (callId != null && (callId.isBlank() || !callId.equals(callId.strip()))) {
            throw new IllegalArgumentException("callId must be non-blank and trimmed when present");
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
