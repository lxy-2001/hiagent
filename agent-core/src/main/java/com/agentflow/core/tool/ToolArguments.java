package com.agentflow.core.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, provider-neutral tool arguments with bounded recursive values. */
public final class ToolArguments {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_PROPERTIES = 32;
    public static final int MAX_STRING_CHARS = 4096;

    private final Map<String, Object> values;

    public ToolArguments(Map<String, ?> values) {
        Objects.requireNonNull(values, "values must not be null");
        this.values = immutableMap(values, 1);
    }

    public Map<String, Object> values() {
        return values;
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("tool arguments exceed maximum depth");
        }
        if (source.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("tool arguments exceed maximum properties");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "argument name must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("argument name must not be blank");
            }
            copy.put(key, immutableValue(entry.getValue(), depth));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String string) {
            if (string.length() > MAX_STRING_CHARS) {
                throw new IllegalArgumentException("tool argument string exceeds maximum length");
            }
            return string;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("tool argument number must be finite");
            }
            return value;
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("tool argument number must be finite");
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("nested argument names must be strings");
                }
                stringMap.put(key, entry.getValue());
            }
            return immutableMap(stringMap, depth + 1);
        }
        if (value instanceof List<?> list) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("tool arguments exceed maximum depth");
            }
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(immutableValue(item, depth + 1));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("unsupported tool argument value: "
                + value.getClass().getName());
    }
}
