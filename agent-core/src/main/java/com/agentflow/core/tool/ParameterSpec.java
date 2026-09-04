package com.agentflow.core.tool;

import java.util.Set;

public record ParameterSpec(
        ValueType type,
        boolean required,
        boolean nullable,
        String description,
        Integer minLength,
        Integer maxLength,
        Long minInteger,
        Long maxInteger,
        Double minNumber,
        Double maxNumber,
        Set<String> enumValues,
        String pattern
) {
    public ParameterSpec {
        if (type == null) {
            throw new NullPointerException("type must not be null");
        }
        description = description == null ? "" : description;
        if (minLength != null && minLength < 0) {
            throw new IllegalArgumentException("minLength must not be negative");
        }
        if (maxLength != null && maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException("minLength must not exceed maxLength");
        }
        if (minInteger != null && maxInteger != null && minInteger > maxInteger) {
            throw new IllegalArgumentException("minInteger must not exceed maxInteger");
        }
        if ((minNumber != null && !Double.isFinite(minNumber))
                || (maxNumber != null && !Double.isFinite(maxNumber))) {
            throw new IllegalArgumentException("number bounds must be finite");
        }
        if (minNumber != null && maxNumber != null && minNumber > maxNumber) {
            throw new IllegalArgumentException("minNumber must not exceed maxNumber");
        }
        enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
        if (pattern != null && pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        if ((minLength != null || maxLength != null || pattern != null) && type != ValueType.STRING) {
            throw new IllegalArgumentException("string constraints require STRING type");
        }
        if ((minInteger != null || maxInteger != null) && type != ValueType.INTEGER) {
            throw new IllegalArgumentException("integer bounds require INTEGER type");
        }
        if ((minNumber != null || maxNumber != null) && type != ValueType.NUMBER) {
            throw new IllegalArgumentException("number bounds require NUMBER type");
        }
    }

    public ParameterSpec(ValueType type, boolean required, boolean nullable) {
        this(type, required, nullable, "", null, null, null, null, null, null, Set.of(), null);
    }

    public static ParameterSpec requiredString(int maxLength) {
        return new ParameterSpec(ValueType.STRING, true, false, "", null, maxLength,
                null, null, null, null, Set.of(), null);
    }

    public static ParameterSpec requiredString() {
        return new ParameterSpec(ValueType.STRING, true, false);
    }
}
