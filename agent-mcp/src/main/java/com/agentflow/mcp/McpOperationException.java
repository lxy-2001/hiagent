package com.agentflow.mcp;

/** Stable public code, deliberately excludes transport messages and credentials. */
final class McpOperationException extends RuntimeException {
    private final String code;
    McpOperationException(String code) { super(code); this.code=code; }
    String code() { return code; }
}
