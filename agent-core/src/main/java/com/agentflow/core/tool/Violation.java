package com.agentflow.core.tool;

import java.util.Objects;

public record Violation(String path, String code, String message) {
    public Violation {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
