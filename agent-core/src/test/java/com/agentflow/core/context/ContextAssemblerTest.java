package com.agentflow.core.context;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextAssemblerTest {
    @TestFactory
    Stream<DynamicTest> deterministicWindowBoundaries() {
        return IntStream.range(1, 121).mapToObj(window -> DynamicTest.dynamicTest("window-" + window, () -> {
            var assembler = assembler(window);
            var request = new AgentRequest("r", "s", "u", "x");
            var result = assembler.assemble(request, List.of(ModelMessage.user("x")), List.of(), 1, 8);
            // system:32+6+1, current:32+4+1, reserve:8 => 84
            assertEquals(window >= 84, result.ready());
            assertTrue(result.diagnostics().summary().length() <= 1024);
            if (result.ready()) {
                assertEquals(76, result.diagnostics().estimatedInput());
                assertEquals(8, result.request().maxCompletionTokens());
                assertEquals(List.of("system", "user"), result.request().messages().stream().map(ModelMessage::role).toList());
            } else {
                assertNull(result.request());
                assertEquals("CONTEXT_BUDGET_EXCEEDED", result.rejectionReason());
            }
        }));
    }

    @Test
    void dropsOldestCompleteTurnAndDoesNotDeduplicateIdenticalTexts() {
        var seed = new ContextSeed(2, List.of(new ConversationTurn("r1", 1, "x", "y"),
                new ConversationTurn("r2", 2, "x", "y")), List.of(), false, 0);
        var request = new AgentRequest("r3", "s", "u", "z", seed);
        var result = assembler(163).assemble(request, List.of(ModelMessage.user("z")), List.of(), 1, 8);
        assertTrue(result.ready());
        assertEquals(1, result.diagnostics().keptTurns());
        assertEquals("WINDOW", result.diagnostics().selections().get(0).reason());
        assertEquals("KEEP", result.diagnostics().selections().get(1).reason());
        var full = assembler(300).assemble(request, List.of(ModelMessage.user("z")), List.of(), 1, 8);
        assertEquals(2, full.diagnostics().keptTurns());
        assertEquals(List.of("s", "x", "y", "x", "y", "z"), full.request().messages().stream().map(ModelMessage::content).toList());
    }

    @Test
    void keepsCurrentToolPairsButRejectsUnknownRolesOrUnpairedCalls() {
        var request = new AgentRequest("r", "s", "u", "x");
        var call = new ToolCall("c", "echo", new ToolArguments(Map.of()));
        var chain = List.of(ModelMessage.user("x"), ModelMessage.assistantToolCall(call),
                new ModelMessage("tool", "observation", "echo", "c", null));
        assertTrue(assembler(1000).assemble(request, chain, List.of(), 2, 8).ready());
        var rejected = assembler(84).assemble(request, chain, List.of(), 2, 8);
        assertFalse(rejected.ready());
        assertEquals("CONTEXT_BUDGET_EXCEEDED", rejected.rejectionReason());
        assertEquals("INVALID_INPUT", assembler(1000).assemble(request,
                List.of(ModelMessage.user("x"), ModelMessage.assistantToolCall(call)), List.of(), 1, 8).rejectionReason());
        assertEquals("INVALID_INPUT", assembler(1000).assemble(request,
                List.of(new ModelMessage("system", "x")), List.of(), 1, 8).rejectionReason());
    }

    @Test
    void dropsLanguageBeforeStackAndPreservesSeedAcrossCalls() {
        var seed = new ContextSeed(0, List.of(), List.of(
                new ConfirmedMemory("preferred_language", "USER_PREFERENCE", "Java", 1, "USER_CONFIRMED"),
                new ConfirmedMemory("project_stack", "PROJECT_FACT", "Java 17", 2, "USER_CONFIRMED")), false, 0);
        var request = new AgentRequest("r", "s", "u", "x", seed);
        var roomy = assembler(1000).assemble(request, List.of(ModelMessage.user("x")), List.of(), 1, 8);
        int limit = (int) roomy.diagnostics().estimatedInput() + 7;
        var tight = assembler(limit).assemble(request, List.of(ModelMessage.user("x")), List.of(), 1, 8);
        assertTrue(tight.ready());
        assertEquals(1, tight.diagnostics().keptMemory());
        assertEquals("WINDOW", tight.diagnostics().selections().get(0).reason());
        assertEquals("KEEP", tight.diagnostics().selections().get(1).reason());
        assertEquals(2, roomy.diagnostics().keptMemory());
        assertEquals(2, seed.memories().size());
    }

    @Test
    void rejectsNegativeOrOverflowingEstimatorAndNeverLeaksInputIntoSummary() {
        var request = new AgentRequest("r", "s", "u", "sensitive text");
        TokenEstimator estimator = new TokenEstimator() {
            public long estimateInput(List<ModelMessage> messages, List<com.agentflow.core.tool.ToolDefinition> tools) {
                return Long.MAX_VALUE;
            }
            public String version() { return "test"; }
        };
        var result = new ContextAssembler(new ContextPolicy("s", "p", 1000), estimator, new ContextTextPolicy())
                .assemble(request, List.of(ModelMessage.user(request.input())), List.of(), 1, 8);
        assertEquals("CONTEXT_BUDGET_EXCEEDED", result.rejectionReason());
        assertFalse(result.diagnostics().summary().contains("sensitive text"));
    }

    @Test
    void customEstimatorCannotBypassToolMetadataLimit() {
        TokenEstimator zero = new TokenEstimator() {
            public long estimateInput(List<ModelMessage> messages, List<com.agentflow.core.tool.ToolDefinition> tools) { return 0; }
            public String version() { return "zero"; }
        };
        var tool = new com.agentflow.core.tool.ToolDefinition("echo", "x".repeat(65537), com.agentflow.core.tool.RiskLevel.LOW,
                new com.agentflow.core.tool.ToolSchema(Map.of(), java.util.Set.of(), false));
        var result = new ContextAssembler(new ContextPolicy("s", "p", 1000), zero, new ContextTextPolicy())
                .assemble(new AgentRequest("r", "s", "u", "x"), List.of(ModelMessage.user("x")), List.of(tool), 1, 8);
        assertEquals("CONTEXT_BUDGET_EXCEEDED", result.rejectionReason());
        assertNull(result.request());
    }

    private static ContextAssembler assembler(long window) {
        return new ContextAssembler(new ContextPolicy("s", "p", window), new Utf8TokenEstimator(), new ContextTextPolicy());
    }
}
