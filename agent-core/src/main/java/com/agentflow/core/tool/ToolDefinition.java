package com.agentflow.core.tool;

import java.util.Objects;

public record ToolDefinition(
        String name,
        String description,
        RiskLevel riskLevel,
        ToolSchema schema
) {
    public ToolDefinition {
        requireName(name);
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
    }

    private static void requireName(String value) {
        Objects.requireNonNull(value, "name must not be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("name must be non-blank and trimmed");
        }
    }
}
