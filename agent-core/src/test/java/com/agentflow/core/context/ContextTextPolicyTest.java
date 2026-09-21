package com.agentflow.core.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextTextPolicyTest {
    private final ContextTextPolicy policy = new ContextTextPolicy();
    @Test void redactsKnownSecretsButKeepsNormalCode() {
        assertEquals("token=[redacted]", policy.sanitizeInput("token=abc"));
        assertEquals("Bearer [redacted]", policy.sanitizeInput("Bearer abc.def"));
        assertEquals("if (x == 1) {}", policy.sanitizeInput("if (x == 1) {}"));
    }
    @Test void rejectsSecretsAndOversizedMemory() {
        assertFalse(policy.isMemoryValueAllowed("token=abc"));
        assertFalse(policy.isMemoryValueAllowed("x".repeat(513)));
        assertTrue(policy.isMemoryValueAllowed("Java 17"));
    }
    @Test void preservesQueryParametersAndRedactionIsIdempotent() {
        String input = "/path?token=test-value&lang=Java";
        String expected = "/path?token=[redacted]&lang=Java";
        assertEquals(expected, policy.sanitizeInput(input));
        assertEquals(expected, policy.sanitizeInput(expected));
        assertEquals("😀 Java", policy.sanitizeHistory("😀 Java"));
    }
    @Test void rejectsUnboundedWorkAndDetectsExpansion() {
        assertThrows(IllegalArgumentException.class, () -> policy.sanitizeInput("x".repeat(65537)));
        assertEquals("x".repeat(7993) + " token=[redacted]",
                policy.sanitizeInput("x".repeat(7993) + " token=x"));
    }
}
