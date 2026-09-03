package com.agentflow.core.tool;

import java.util.Objects;

/** Converts untrusted Tool output into a bounded, call-correlated envelope. */
public final class DefaultToolResultNormalizer implements ToolResultNormalizer {
    public static final int MAX_OUTPUT_CHARS = 8192;

    @Override
    public ToolResult normalize(ToolCall call, ToolResult raw) {
        Objects.requireNonNull(call, "call must not be null");
        if (raw == null) {
            return failure(call, "TOOL_RESULT_INVALID", "tool returned null result");
        }
        if (!call.name().equals(raw.toolName())) {
            return failure(call, "TOOL_RESULT_INVALID", "tool result name does not match call");
        }
        if (raw.callId() != null && !call.callId().equals(raw.callId())) {
            return failure(call, "TOOL_RESULT_INVALID", "tool result call id does not match call");
        }
        String output = raw.output();
        if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            return failure(call, "TOOL_RESULT_TOO_LARGE", "tool result exceeds output limit");
        }
        output = sanitize(output);
        if (raw.status() == ToolResultStatus.SUCCESS) {
            if (output == null || output.isBlank()) {
                return failure(call, "TOOL_RESULT_INVALID", "successful tool result must have output");
            }
            return new ToolResult(call.name(), output, ToolResultStatus.SUCCESS, null,
                    sanitize(raw.diagnostic()), raw.truncated(), call.callId());
        }
        if (raw.errorCode() == null || raw.errorCode().isBlank()) {
            return failure(call, "TOOL_RESULT_INVALID", "failed tool result must have error code");
        }
        return new ToolResult(call.name(), output, ToolResultStatus.FAILED, raw.errorCode(),
                sanitize(raw.diagnostic()), raw.truncated(), call.callId());
    }

    private static ToolResult failure(ToolCall call, String code, String diagnostic) {
        return ToolResult.failure(call.name(), call.callId(), code, sanitize(diagnostic));
    }

    static String sanitize(String diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        String value = diagnostic
                .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
