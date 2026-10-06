package com.agentflow.core.tool;

import java.util.Objects;

/** Converts untrusted Tool output into a bounded, call-correlated envelope. */
public final class DefaultToolResultNormalizer implements ToolResultNormalizer {
    public static final int MAX_OUTPUT_CHARS = 8192;
    public static final long MAX_MANAGED_OUTPUT_BYTES = 64L * 1024 * 1024;

    @Override
    public ToolResult normalize(ToolCall call, ToolResult raw) {
        return normalize(call, raw, false);
    }

    @Override
    public ToolResult normalizeManaged(ToolCall call, ToolResult raw) {
        return normalize(call, raw, true);
    }

    private ToolResult normalize(ToolCall call, ToolResult raw, boolean managed) {
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
        if (raw.status() == ToolResultStatus.SUCCESS && "knowledge.search".equals(call.name())
                && raw.retrievalPayload() == null) {
            return failure(call, "TOOL_RESULT_INVALID", "retrieval result requires source evidence");
        }
        // Retrieval retains its existing evidence limits; only ordinary persisted outputs are externalized.
        managed = managed && !"knowledge.search".equals(call.name()) && raw.retrievalPayload() == null;
        String output = raw.output();
        boolean truncated = raw.truncated();
        if (managed) {
            BoundedOutput bounded = boundManaged(output);
            output = bounded.output();
            truncated |= bounded.truncated();
        } else if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            return failure(call, "TOOL_RESULT_TOO_LARGE", "tool result exceeds output limit");
        }
        output = redact(output);
        if (managed) {
            BoundedOutput bounded = boundManaged(output);
            output = bounded.output();
            truncated |= bounded.truncated();
        } else if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            return failure(call, "TOOL_RESULT_TOO_LARGE", "redacted tool result exceeds output limit");
        }
        if (raw.retrievalPayload() != null) {
            if (!Objects.equals(output, raw.output()) || raw.retrievalPayload().hits().stream()
                    .anyMatch(hit -> !hit.text().equals(redact(hit.text()))
                            || !hit.title().equals(redact(hit.title()))
                            || !hit.relativePath().equals(redact(hit.relativePath())))) {
                return failure(call, "RAG_SOURCE_INVALID", "source requires redaction");
            }
        }
        if (raw.status() == ToolResultStatus.SUCCESS) {
            if (output == null || output.isBlank()) {
                return failure(call, "TOOL_RESULT_INVALID", "successful tool result must have output");
            }
            return new ToolResult(call.name(), output, ToolResultStatus.SUCCESS, null,
                    sanitize(raw.diagnostic()), truncated, call.callId(), raw.retrievalPayload());
        }
        if (raw.errorCode() == null || raw.errorCode().isBlank()) {
            return failure(call, "TOOL_RESULT_INVALID", "failed tool result must have error code");
        }
        return new ToolResult(call.name(), output, ToolResultStatus.FAILED,
                safeErrorCode(raw.errorCode()), sanitize(raw.diagnostic()), truncated, call.callId());
    }

    private record BoundedOutput(String output, boolean truncated) { }

    /** Retains a UTF-8 prefix without splitting a surrogate pair or changing execution status. */
    private static BoundedOutput boundManaged(String output) {
        if (output == null) return new BoundedOutput(null, false);
        long bytes = 0;
        for (int index = 0; index < output.length();) {
            char c = output.charAt(index);
            boolean pair = Character.isHighSurrogate(c) && index + 1 < output.length()
                    && Character.isLowSurrogate(output.charAt(index + 1));
            int width = pair ? 4 : c < 0x80 ? 1 : c < 0x800 ? 2
                    : Character.isSurrogate(c) ? 1 : 3;
            if (bytes + width > MAX_MANAGED_OUTPUT_BYTES) {
                return new BoundedOutput(output.substring(0, index), true);
            }
            bytes += width;
            index += pair ? 2 : 1;
        }
        return new BoundedOutput(output, false);
    }

    private static String safeErrorCode(String code) {
        return code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "TOOL_ERROR";
    }

    private static ToolResult failure(ToolCall call, String code, String diagnostic) {
        return ToolResult.failure(call.name(), call.callId(), code, sanitize(diagnostic));
    }

    static String sanitize(String diagnostic) {
        String value = redact(diagnostic);
        if (value == null || value.length() <= 1024) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(1023)) ? 1023 : 1024;
        return value.substring(0, end);
    }

    private static String redact(String diagnostic) {
        if (diagnostic == null) {
            return null;
        }
        return diagnostic
                .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]")
                .replaceAll("(?i)([?&](?:api[-_ ]?key|token|secret|password)=)[^&\\s]+", "$1[redacted]");
    }
}
