package com.agentflow.core.tool;

import java.util.List;
import java.util.Objects;

public record ValidationResult(boolean valid, ToolArguments arguments, List<Violation> violations) {
    public ValidationResult {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(violations, "violations must not be null");
        arguments = new ToolArguments(arguments.values());
        violations = List.copyOf(violations);
        if (valid && !violations.isEmpty()) {
            throw new IllegalArgumentException("valid result cannot contain violations");
        }
        if (!valid && violations.isEmpty()) {
            throw new IllegalArgumentException("invalid result must contain violations");
        }
    }

    public static ValidationResult valid(ToolArguments arguments) {
        return new ValidationResult(true, arguments, List.of());
    }

    public static ValidationResult invalid(ToolArguments arguments, List<Violation> violations) {
        return new ValidationResult(false, arguments, violations);
    }
}
