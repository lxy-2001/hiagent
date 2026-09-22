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
    Stream<DynamicTest> multilingualCompleteToolChainsAtWindowBoundaries() {
        var tests = new java.util.ArrayList<DynamicTest>();
        String[] samples = {"A", "中文", "😀"};
        int[] bytes = {1, 6, 4};
        for (int sample = 0; sample < samples.length; sample++) {
            for (int repeat = 1; repeat <= 4; repeat++) {
                for (boolean withTool : new boolean[]{false, true}) {
                    String input = samples[sample].repeat(repeat);
                    int textBytes = bytes[sample] * repeat;
                    int reserve = repeat % 2 == 0 ? 0 : 8;
                    // system(s)=39; user=36+UTF8; call(c,t,{q:x})=55; result=38+UTF8.
                    long mandatory = withTool ? 168 + 2L * textBytes : 75 + textBytes;
                    for (int offset : new int[]{-1, 0, 1, 23, 57}) {
                        int window = (int) mandatory + reserve + offset;
                        tests.add(DynamicTest.dynamicTest("text-" + sample + "-repeat-" + repeat + "-tool-" + withTool + "-offset-" + offset, () -> {
                            var request = new AgentRequest("r", "s", "u", input);
                            var call = new ToolCall("c", "t", new ToolArguments(Map.of("q", "x")));
                            var chain = withTool ? List.of(ModelMessage.user(input), ModelMessage.assistantToolCall(call),
                                    new ModelMessage("tool", input, "t", "c", null)) : List.of(ModelMessage.user(input));
                            var result = assembler(window).assemble(request, chain, List.of(), 1, reserve);
                            assertEquals(offset >= 0, result.ready());
                            if (result.ready()) {
                                assertEquals(mandatory, result.diagnostics().estimatedInput());
                                assertEquals(reserve, result.request().maxCompletionTokens());
                                assertEquals(chain, result.request().messages().subList(1, result.request().messages().size()));
                            } else {
                                assertNull(result.request());
                                assertEquals("CONTEXT_BUDGET_EXCEEDED", result.rejectionReason());
                            }
                        }));
                    }
                }
            }
        }
        return tests.stream();
    }

    @Test
    void optionalHistoryDropsWholeOldestPairsAtMessageAndTextHardLimits() {
        TokenEstimator zero = new TokenEstimator() {
            public long estimateInput(List<ModelMessage> messages, List<com.agentflow.core.tool.ToolDefinition> tools) { return 0; }
            public String version() { return "zero-test"; }
        };
        var bounded = new ContextAssembler(new ContextPolicy("s", "p", 131072), zero, new ContextTextPolicy());
        var history = IntStream.rangeClosed(1, 20).mapToObj(i -> new ConversationTurn("old-" + i, i, "q", "a")).toList();
        var chain = new java.util.ArrayList<ModelMessage>();
        chain.add(ModelMessage.user("current"));
        for (int i = 0; i < 12; i++) {
            var call = new ToolCall("c" + i, "t", new ToolArguments(Map.of()));
            chain.add(ModelMessage.assistantToolCall(call));
            chain.add(new ModelMessage("tool", "result", "t", "c" + i, null));
        }
        var result = bounded.assemble(new AgentRequest("r", "s", "u", "current", new ContextSeed(20, history, List.of(), false, 0)), chain, List.of(), 13, 0);
        assertTrue(result.ready());
        assertEquals(64, result.request().messages().size());
        assertEquals(19, result.diagnostics().keptTurns());
        assertEquals("MESSAGE_LIMIT", result.diagnostics().selections().get(0).reason());
        var largeHistory = IntStream.rangeClosed(1, 5).mapToObj(i -> new ConversationTurn("large-" + i, i, "q", "a".repeat(65536))).toList();
        var textLimited = bounded.assemble(new AgentRequest("r", "s", "u", "current", new ContextSeed(5, largeHistory, List.of(), false, 0)),
                List.of(ModelMessage.user("current")), List.of(), 1, 0);
        assertTrue(textLimited.ready());
        assertEquals(3, textLimited.diagnostics().keptTurns());
        assertEquals(2, textLimited.diagnostics().droppedTurns());
        assertEquals("TEXT_LIMIT", textLimited.diagnostics().selections().get(0).reason());
    }
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
