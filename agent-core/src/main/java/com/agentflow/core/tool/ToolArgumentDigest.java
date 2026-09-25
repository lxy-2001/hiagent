package com.agentflow.core.tool;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Small bounded encoder for the hiagent fingerprint format, not a JSON parser. */
public final class ToolArgumentDigest {
    public static final int MAX_ARGUMENT_BYTES = 32 * 1024;
    private ToolArgumentDigest() { }

    public static String digest(ToolArguments arguments) {
        return fingerprint("hiagent-tool-args-v1", arguments.values(), MAX_ARGUMENT_BYTES);
    }

    public static String definitionVersion(ToolDefinition definition) {
        Map<String,Object> properties = new TreeMap<>();
        definition.schema().properties().forEach((name, spec) -> {
            Map<String,Object> fields = new TreeMap<>();
            fields.put("type", spec.type().name()); fields.put("required", spec.required());
            fields.put("nullable", spec.nullable()); fields.put("description", spec.description());
            fields.put("minLength", spec.minLength()); fields.put("maxLength", spec.maxLength());
            fields.put("minInteger", spec.minInteger()); fields.put("maxInteger", spec.maxInteger());
            fields.put("minNumber", spec.minNumber()); fields.put("maxNumber", spec.maxNumber());
            fields.put("enum", spec.enumValues().stream().sorted().toList()); fields.put("pattern", spec.pattern());
            properties.put(name, fields);
        });
        return fingerprint("hiagent-tool-definition-v1", Map.of("name", definition.name(),
                "description", definition.description(), "risk", definition.riskLevel().name(),
                "properties", properties, "required", definition.schema().required().stream().sorted().toList(),
                "additionalProperties", definition.schema().allowAdditionalProperties()), 128 * 1024);
    }

    static String fingerprint(String prefix, Object value, int limit) {
        StringBuilder encoded = new StringBuilder();
        append(value, encoded, limit);
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IllegalArgumentException("fingerprint input exceeds byte limit");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void append(Object value, StringBuilder out, int limit) {
        if (value == null || value instanceof Boolean) out.append(value);
        else if (value instanceof String text) string(text, out, limit);
        else if (value instanceof Number number) {
            if (number instanceof Float || number instanceof Double) {
                double numeric = number.doubleValue();
                if (!Double.isFinite(numeric)) throw new IllegalArgumentException("non-finite number");
                out.append(BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString());
            } else out.append(number);
        } else if (value instanceof Map<?,?> map) {
            out.append('{'); boolean first = true;
            for (Object key : map.keySet().stream().sorted().toList()) {
                if (!first) out.append(','); first = false;
                string((String) key, out, limit); out.append(':'); append(map.get(key), out, limit);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('['); boolean first = true;
            for (Object item : list) {
                if (!first) out.append(','); first = false; append(item, out, limit);
            }
            out.append(']');
        } else throw new IllegalArgumentException("unsupported fingerprint value");
        checkLength(out, limit);
    }

    private static void string(String text, StringBuilder out, int limit) {
        out.append('"');
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isHighSurrogate(c)) {
                        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                            throw new IllegalArgumentException("unpaired surrogate");
                        out.append(c).append(text.charAt(i));
                    } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("unpaired surrogate");
                    else if (c < 32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else out.append(c);
                }
            }
            checkLength(out, limit);
        }
        out.append('"');
    }
    private static void checkLength(StringBuilder out, int limit) {
        if (out.length() > limit) throw new IllegalArgumentException("fingerprint input exceeds byte limit");
    }
}
