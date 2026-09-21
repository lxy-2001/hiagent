package com.agentflow.core.context;

import java.util.Objects;

/** One of the two explicitly user-confirmed memory slots. */
public record ConfirmedMemory(String key, String type, String value, long version, String source) {
    public static final String PREFERRED_LANGUAGE = "preferred_language";
    public static final String PROJECT_STACK = "project_stack";
    public static final String USER_CONFIRMED = "USER_CONFIRMED";
    public ConfirmedMemory {
        Objects.requireNonNull(key, "key must not be null");
        if (!PREFERRED_LANGUAGE.equals(key) && !PROJECT_STACK.equals(key)) {
            throw new IllegalArgumentException("unsupported memory key");
        }
        Objects.requireNonNull(type, "type must not be null");
        String expectedType = PREFERRED_LANGUAGE.equals(key) ? "USER_PREFERENCE" : "PROJECT_FACT";
        if (!expectedType.equals(type)) {
            throw new IllegalArgumentException("memory type does not match its key");
        }
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("memory value length");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (!USER_CONFIRMED.equals(source)) {
            throw new IllegalArgumentException("source must be USER_CONFIRMED");
        }
    }
}
