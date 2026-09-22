package com.agentflow.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import com.agentflow.core.rag.RetrievalPayload;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultBodyBoundaryTest {
    private final DefaultToolResultNormalizer normalizer = new DefaultToolResultNormalizer();
    private final ToolCall call = new ToolCall("c", "echo", new ToolArguments(Map.of()));

    @Test
    void retainsWholeBodyAt1025And8192ButRejects8193() {
        for (int size : new int[]{1025, 8192}) {
            String body = "x".repeat(size - 2) + "😀";
            var result = normalizer.normalize(call, ToolResult.success("echo", body));
            assertEquals(body, result.output());
            assertEquals(result, normalizer.normalize(call, result));
        }
        assertEquals("TOOL_RESULT_TOO_LARGE", normalizer.normalize(call,
                ToolResult.success("echo", "x".repeat(8193))).errorCode());
    }

    @Test
    void checksSizeAgainAfterRedactionAndKeepsRedactionIdempotent() {
        String body = "x".repeat(8184) + " token=a";
        assertEquals("TOOL_RESULT_TOO_LARGE", normalizer.normalize(call, ToolResult.success("echo", body)).errorCode());
        var first = normalizer.normalize(call, ToolResult.success("echo", "token=abc&other=yes"));
        assertEquals(first, normalizer.normalize(call, first));
        assertFalse(first.output().contains("abc"));
        var diagnostic = new ToolResult("echo", "ok", ToolResultStatus.SUCCESS, null,
                "x".repeat(1023) + "😀", false, null);
        var result = normalizer.normalize(call, diagnostic);
        assertEquals(1023, result.diagnostic().length());
    }

    @Test
    void reservedRetrievalToolCannotReturnSuccessWithoutEvidence() {
        var search = new ToolCall("s", "knowledge.search", new ToolArguments(Map.of()));
        assertEquals("TOOL_RESULT_INVALID", normalizer.normalize(search,
                ToolResult.success("knowledge.search", "retrieval pending runtime binding")).errorCode());
    }

    @Test
    void preservesRetrievalPayloadAndRejectsNormalizerThatDropsIt() {
        var payload = new RetrievalPayload("a".repeat(64), RetrievalPayload.Mode.HYBRID,
                RetrievalPayload.SemanticState.OK, RetrievalPayload.KeywordState.OK, null,
                RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
        var search = new ToolCall("s", "knowledge.search", new ToolArguments(Map.of()));
        var raw = ToolResult.retrieval("knowledge.search", payload);
        assertThrows(IllegalArgumentException.class, () -> com.agentflow.core.model.ModelMessage.toolResult(raw));
        var result = normalizer.normalize(search, raw);
        assertEquals(payload, result.retrievalPayload());
        assertEquals(result, normalizer.normalize(search, result));
        assertFalse(result.truncated());
        AgentTool tool = new AgentTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("knowledge.search", "search", RiskLevel.LOW, new ToolSchema(Map.of()));
            }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) { return raw; }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public void register(ToolRegistration registration) { throw new UnsupportedOperationException(); }
            @Override public ToolLookup lookup(String name) {
                return new ToolLookup(ToolAvailability.ENABLED, new ToolRegistration(tool, true));
            }
            @Override public List<ToolDefinition> enabledDefinitions() { return List.of(tool.definition()); }
        };
        var executor = new DefaultToolExecutor(registry,
                (call, value) -> ToolResult.success(value.toolName(), value.output()));
        assertEquals("TOOL_RESULT_INVALID", executor.execute(search, new ToolContext("t", "s", "u")).errorCode());
        assertThrows(IllegalArgumentException.class, () -> ToolResult.retrieval("echo", payload));
    }
}
