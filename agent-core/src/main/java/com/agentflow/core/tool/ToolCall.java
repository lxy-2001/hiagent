package com.agentflow.core.tool;

import java.util.Objects;

public record ToolCall(String callId, String name, ToolArguments arguments) {
    public ToolCall {
        requireNonBlank(callId, "callId");
        requireNonBlank(name, "name");
        Objects.requireNonNull(arguments, "arguments must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must not have surrounding whitespace");
        }
    }
}
