package com.agentflow.core.context;

/** Stable failure from a durable context boundary, never permission to replay work. */
public final class ContextSessionException extends RuntimeException {
    private final String code;
    public ContextSessionException(String code) { this(code, null); }
    public ContextSessionException(String code, Throwable cause) {
        super(code, cause);
        this.code = java.util.Objects.requireNonNull(code);
    }
    public String code() { return code; }
}
