package com.agentflow.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultNormalizerTest {
    private final ToolCall call = new ToolCall("call-1", "echo", new ToolArguments(Map.of()));
    private final DefaultToolResultNormalizer normalizer = new DefaultToolResultNormalizer();

    @Test
    void injectsCallIdAndCanonicalNameForValidResult() {
        ToolResult result = normalizer.normalize(call, ToolResult.success("echo", "ok"));

        assertEquals("call-1", result.callId());
        assertEquals("echo", result.toolName());
        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertEquals("ok", result.output());
    }

    @Test
    void rejectsNullEmptyMismatchedAndOverlongResults() {
        assertFailure(normalizer.normalize(call, null), "TOOL_RESULT_INVALID");
        assertFailure(normalizer.normalize(call, new ToolResult("echo", "", ToolResultStatus.SUCCESS, null, null, false, null)), "TOOL_RESULT_INVALID");
        assertFailure(normalizer.normalize(call, ToolResult.success("other", "ok")), "TOOL_RESULT_INVALID");
        assertFailure(normalizer.normalize(call, new ToolResult("echo", "x".repeat(8193), ToolResultStatus.SUCCESS, null, null, false, null)), "TOOL_RESULT_TOO_LARGE");
    }

    @Test
    void convertsExceptionLikeFailureAndRedactsSensitiveDiagnostic() {
        ToolResult raw = new ToolResult("echo", null, ToolResultStatus.FAILED, "TOOL_ERROR",
                "apiKey=secret-value", false, null);

        ToolResult result = normalizer.normalize(call, raw);

        assertEquals(ToolResultStatus.FAILED, result.status());
        assertEquals("TOOL_ERROR", result.errorCode());
        assertTrue(result.diagnostic().contains("[redacted]"));
        assertFalse(result.diagnostic().contains("secret-value"));
        assertEquals("call-1", result.callId());
    }

    @Test
    void redactsSensitiveToolOutputBeforeItCanReachTheNextModelTurn() {
        ToolResult result = normalizer.normalize(call,
                ToolResult.success("echo", "apiKey=secret-value Bearer bearer-secret"));

        assertTrue(result.output().contains("[redacted]"));
        assertFalse(result.output().contains("secret-value"));
        assertFalse(result.output().contains("bearer-secret"));
    }

    private static void assertFailure(ToolResult result, String code) {
        assertEquals(ToolResultStatus.FAILED, result.status());
        assertEquals(code, result.errorCode());
        assertEquals("call-1", result.callId());
    }
}
