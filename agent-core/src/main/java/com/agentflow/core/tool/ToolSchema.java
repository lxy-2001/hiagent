package com.agentflow.core.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public record ToolSchema(
        Map<String, ParameterSpec> properties,
        Set<String> required,
        boolean allowAdditionalProperties
) {
    public ToolSchema {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(required, "required must not be null");
        Map<String, ParameterSpec> propertyCopy = new java.util.TreeMap<>();
        for (Map.Entry<String, ParameterSpec> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || !entry.getKey().equals(entry.getKey().strip())) {
                throw new IllegalArgumentException("property names must be non-blank and trimmed");
            }
            propertyCopy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(),
                    "parameter spec must not be null"));
        }
        Set<String> requiredCopy = Set.copyOf(required);
        if (!propertyCopy.keySet().containsAll(requiredCopy)) {
            throw new IllegalArgumentException("required properties must be declared");
        }
        properties = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(propertyCopy));
        required = requiredCopy;
    }

    public ToolSchema(Map<String, ParameterSpec> properties) {
        this(properties, Set.of(), false);
    }

    public ValidationResult validate(ToolArguments arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> values = arguments.values();
        for (String requiredName : required.stream().sorted().toList()) {
            if (!values.containsKey(requiredName)) {
                violations.add(new Violation(requiredName, "MISSING_REQUIRED",
                        "required argument is missing"));
            }
        }
        if (!allowAdditionalProperties) {
            values.keySet().stream()
                    .filter(name -> !properties.containsKey(name))
                    .sorted()
                    .forEach(name -> violations.add(new Violation(name, "UNKNOWN_PROPERTY",
                            "argument is not declared by the tool schema")));
        }
        properties.keySet().stream().sorted().forEach(name -> {
            if (values.containsKey(name)) {
                validateValue(name, values.get(name), properties.get(name), violations);
            }
        });
        violations.sort(Comparator.comparing(Violation::path).thenComparing(Violation::code));
        return violations.isEmpty()
                ? ValidationResult.valid(arguments)
                : ValidationResult.invalid(arguments, violations);
    }

    private void validateValue(String name, Object value, ParameterSpec spec,
                               List<Violation> violations) {
        if (value == null) {
            if (!spec.nullable()) {
                violations.add(new Violation(name, "NULL_NOT_ALLOWED", "null is not allowed"));
            }
            return;
        }
        boolean typeMatches = switch (spec.type()) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof Double;
            case BOOLEAN -> value instanceof Boolean;
        };
        if (!typeMatches) {
            violations.add(new Violation(name, "TYPE_MISMATCH", "argument type does not match schema"));
            return;
        }
        if (value instanceof String string) {
            if ((spec.minLength() != null && string.length() < spec.minLength())
                    || (spec.maxLength() != null && string.length() > spec.maxLength())) {
                violations.add(new Violation(name, "LENGTH_OUT_OF_RANGE", "string length is outside schema range"));
            }
            if (!spec.enumValues().isEmpty() && !spec.enumValues().contains(string)) {
                violations.add(new Violation(name, "ENUM_MISMATCH", "value is not in schema enum"));
            }
            if (spec.pattern() != null) {
                try {
                    if (!Pattern.matches(spec.pattern(), string)) {
                        violations.add(new Violation(name, "PATTERN_MISMATCH", "value does not match schema pattern"));
                    }
                } catch (PatternSyntaxException ex) {
                    violations.add(new Violation(name, "INVALID_SCHEMA", "schema pattern is invalid"));
                }
            }
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                violations.add(new Violation(name, "NON_FINITE_NUMBER", "number must be finite"));
            }
            if (spec.type() == ValueType.INTEGER && ((spec.minInteger() != null
                    && number.longValue() < spec.minInteger())
                    || (spec.maxInteger() != null && number.longValue() > spec.maxInteger()))) {
                violations.add(new Violation(name, "RANGE_OUT_OF_BOUNDS", "integer is outside schema range"));
            }
            if (spec.type() == ValueType.NUMBER && ((spec.minNumber() != null && numeric < spec.minNumber())
                    || (spec.maxNumber() != null && numeric > spec.maxNumber()))) {
                violations.add(new Violation(name, "RANGE_OUT_OF_BOUNDS", "number is outside schema range"));
            }
        }
    }
}
