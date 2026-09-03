package com.agentflow.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaContractTest {

    private final ToolSchema schema = new ToolSchema(
            Map.of("text", ParameterSpec.requiredString(5)),
            Set.of("text"), false);

    @Test
    void acceptsValidArgumentsAndReturnsImmutableCopy() {
        ToolArguments input = new ToolArguments(Map.of("text", "hello"));

        ValidationResult result = schema.validate(input);

        assertTrue(result.valid());
        assertEquals("hello", result.arguments().values().get("text"));
        assertFalse(result.arguments() == input);
    }

    @Test
    void rejectsMissingUnknownAndWrongTypeBeforeExecution() {
        assertFalse(schema.validate(new ToolArguments(Map.of())).valid());
        assertFalse(schema.validate(new ToolArguments(Map.of("text", "hello", "extra", true))).valid());
        assertFalse(schema.validate(new ToolArguments(Map.of("text", 42))).valid());
    }

    @Test
    void rejectsStringThatExceedsDeclaredLimit() {
        assertFalse(schema.validate(new ToolArguments(Map.of("text", "toolong"))).valid());
    }
    @Test
    void rejectsUntrustedNestedValuesAndResourceLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolArguments(Map.of("object", Map.of(1, "bad"))));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolArguments(Map.of("text", "x".repeat(ToolArguments.MAX_STRING_CHARS + 1))));
        Map<String, Object> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= ToolArguments.MAX_PROPERTIES; i++) {
            tooMany.put("p" + i, i);
        }
        assertThrows(IllegalArgumentException.class, () -> new ToolArguments(tooMany));
    }

}
