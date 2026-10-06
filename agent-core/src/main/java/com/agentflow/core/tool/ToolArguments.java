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

    /** Maximum encoded envelope for a managed, persisted harness call. */
    public static final int MAX_MANAGED_BYTES = 1024 * 1024;

    private final Map<String, Object> values;
    private final boolean managed;

    public ToolArguments(Map<String, ?> values) {
        this(values, false);
    }

    private ToolArguments(Map<String, ?> values, boolean managed) {
        Objects.requireNonNull(values, "values must not be null");
        this.managed = managed;
        this.values = immutableMap(values, 1, managed ? MAX_MANAGED_BYTES : MAX_STRING_CHARS,
                managed ? new EnvelopeBudget() : null);
        if (managed) ToolArgumentDigest.digestManaged(this);
    }

    /** An explicit harness path; the legacy 4096-character constructor is unchanged. */
    public static ToolArguments managed(Map<String, ?> values) {
        return new ToolArguments(values, true);
    }

    public boolean isManaged() { return managed; }

    public Map<String, Object> values() {
        return values;
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source, int depth, int stringLimit, EnvelopeBudget budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("tool arguments exceed maximum depth");
        }
        if (source.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("tool arguments exceed maximum properties");
        }
        if (budget != null) budget.take(2 + source.size());
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "argument name must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("argument name must not be blank");
            }
            if (budget != null) budget.text(key);
            copy.put(key, immutableValue(entry.getValue(), depth, stringLimit, budget));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value, int depth, int stringLimit, EnvelopeBudget budget) {
        if (budget != null) budget.take(1);
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String string) {
            if (string.length() > stringLimit) {
                throw new IllegalArgumentException("tool argument string exceeds maximum length");
            }
            if (budget != null) budget.text(string);
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
            return immutableMap(stringMap, depth + 1, stringLimit, budget);
        }
        if (value instanceof List<?> list) {
            if (depth >= MAX_DEPTH) {
                throw new IllegalArgumentException("tool arguments exceed maximum depth");
            }
            if (budget != null) budget.take(2 + list.size());
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(immutableValue(item, depth + 1, stringLimit, budget));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("unsupported tool argument value: "
                + value.getClass().getName());
    }
    private static final class EnvelopeBudget {
        private long remaining = MAX_MANAGED_BYTES;
        void text(String text) {
            if (text.length() > MAX_MANAGED_BYTES) throw new IllegalArgumentException("managed arguments exceed byte limit");
            take(2L + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        void take(long bytes) {
            remaining -= bytes;
            if (remaining < 0) throw new IllegalArgumentException("managed arguments exceed byte limit");
        }
    }
}
