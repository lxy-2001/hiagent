package com.agentflow.core.runtime;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolRegistration;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolResultNormalizer;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRuntimeTest {

    @Test
    void executesTwoToolsInOrderAndFeedsObservationsIntoFinalDecision() {
        AgentTool toolA = tool("tool-a");
        AgentTool toolB = tool("tool-b");
        TestRegistry registry = new TestRegistry(List.of(toolA, toolB));
        List<ToolCall> calls = new ArrayList<>();
        ToolExecutor executor = (call, context) -> {
            calls.add(call);
            return new ToolResult(call.name(), "result-" + call.name(),
                    com.agentflow.core.tool.ToolResultStatus.SUCCESS, null, null, false, call.callId());
        };
        List<AgentModelRequest> requests = new ArrayList<>();
        AgentModelClient model = request -> {
            requests.add(request);
            return switch (request.iteration()) {
                case 1 -> new ToolCallDecision("decision-1",
                        new ToolCall("call-a", "tool-a", new ToolArguments(Map.of("text", "A"))),
                        new TokenUsage(2, 1, 3));
                case 2 -> new ToolCallDecision("decision-2",
                        new ToolCall("call-b", "tool-b", new ToolArguments(Map.of("text", "B"))),
                        new TokenUsage(3, 1, 4));
                case 3 -> {
                    assertEquals(List.of("result-tool-a", "result-tool-b"),
                            request.messages().stream().filter(m -> "tool".equals(m.role()))
                                    .map(ModelMessage::content).toList());
                    yield new FinalAnswerDecision("decision-3", "done", new TokenUsage(4, 2, 6));
                }
                default -> throw new AssertionError("unexpected iteration " + request.iteration());
            };
        };
        List<com.agentflow.core.AgentStepRecord> recorded = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        DefaultAgentRuntime runtime = RuntimeTestSupport.runtime(model, registry, executor,
                recorded::add, ToolResultNormalizer.IDENTITY);

        AgentResult result = runtime.run(new AgentRequest("task-1", "session-1", "user-1", "hello"),
                events::add);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(TerminationReason.COMPLETED, result.terminationReason());
        assertEquals("done", result.finalAnswer());
        assertEquals(List.of("tool-a", "tool-b"), calls.stream().map(ToolCall::name).toList());
        assertEquals(List.of("result-tool-a"),
                requests.get(1).messages().stream().filter(m -> "tool".equals(m.role()))
                        .map(ModelMessage::content).toList());
        assertEquals(1, events.stream().filter(AgentEvent::terminal).count());
        assertEquals(result.steps().size(), recorded.size());
        assertEquals(AgentStepType.TERMINATION, result.steps().get(result.steps().size() - 1).stepType());
        assertTrue(result.steps().stream().anyMatch(step -> step.stepType() == AgentStepType.FINAL));
    }

    @Test
    void deterministicTwoToolScenarioIsRepeatable() {
        long maxElapsedNanos = 0;
        for (int run = 0; run < 100; run++) {
            long runStarted = System.nanoTime();
            final int runId = run;
            TestRegistry registry = new TestRegistry(List.of(tool("tool-a"), tool("tool-b")));
            List<String> order = new ArrayList<>();
            ToolExecutor executor = (call, context) -> {
                order.add(call.name());
                return new ToolResult(call.name(), "ok-" + call.name(),
                    com.agentflow.core.tool.ToolResultStatus.SUCCESS, null, null, false, call.callId());
            };
            AgentModelClient model = request -> switch (request.iteration()) {
                case 1 -> new ToolCallDecision("d1", new ToolCall("a-" + runId, "tool-a",
                        new ToolArguments(Map.of("text", "a"))), TokenUsage.empty());
                case 2 -> new ToolCallDecision("d2", new ToolCall("b-" + runId, "tool-b",
                        new ToolArguments(Map.of("text", "b"))), TokenUsage.empty());
                case 3 -> new FinalAnswerDecision("d3", "done", TokenUsage.empty());
                default -> throw new AssertionError();
            };
            AgentResult result = RuntimeTestSupport.runtime(model, registry, executor, step -> { },
                    ToolResultNormalizer.IDENTITY).run(new com.agentflow.core.AgentRequest(
                    "task-" + runId, "session", "user", "input"), event -> { });
            assertEquals(RunStatus.SUCCEEDED, result.status());
            assertEquals("done", result.finalAnswer());
            assertEquals(List.of("tool-a", "tool-b"), order);
            maxElapsedNanos = Math.max(maxElapsedNanos, System.nanoTime() - runStarted);
        }
        assertTrue(maxElapsedNanos < 1_000_000_000L,
                "deterministic two-tool scenario exceeded one second");
    }

    @Test
    void canonicalizesSensitiveToolOutputEvenWithIdentityNormalizer() {
        var tool = RuntimeTestSupport.tool("echo", (args, context) ->
                ToolResult.success("echo", "apiKey=secret-value"));
        AgentModelClient model = request -> {
            if (request.iteration() == 1) {
                return new ToolCallDecision("d1",
                        new ToolCall("c1", "echo", new ToolArguments(Map.of())), TokenUsage.empty());
            }
            String toolContent = request.messages().stream()
                    .filter(message -> "tool".equals(message.role()))
                    .findFirst().orElseThrow().content();
            assertTrue(!toolContent.contains("secret-value"));
            assertTrue(toolContent.contains("[redacted]"));
            return new FinalAnswerDecision("d2", "done", TokenUsage.empty());
        };

        AgentResult result = RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(tool),
                (call, context) -> ToolResult.success("echo", "apiKey=secret-value"),
                step -> { }, ToolResultNormalizer.IDENTITY)
                .run(new AgentRequest("t-sensitive", "s", "u", "input"), event -> { });

        assertEquals(RunStatus.SUCCEEDED, result.status());
    }

    @Test
    void convenienceRuntimeUsesRegistryExecutorAndNeverInvokesInvalidArguments() {
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        ToolDefinition definition = new ToolDefinition("tool-a", "tool", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(4)), Set.of("text"), false));
        AgentTool tool = new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("tool-a", "ok");
            }
        };
        TestRegistry registry = new TestRegistry(List.of(tool));
        AgentModelClient model = request -> request.iteration() == 1
                ? new ToolCallDecision("d1", new ToolCall("c1", "tool-a",
                        new ToolArguments(Map.of("text", "too-long"))), TokenUsage.empty())
                : new FinalAnswerDecision("d2", "unexpected", TokenUsage.empty());

        AgentResult result = RuntimeTestSupport.runtime(model, registry, step -> { })
                .run(new com.agentflow.core.AgentRequest("t", "s", "u", "input"), event -> { });

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.INVALID_TOOL_ARGUMENTS, result.terminationReason());
        assertEquals(0, executions.get());
    }

    private static AgentTool tool(String name) {
        ToolDefinition definition = new ToolDefinition(name, name + " tool", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(64)), Set.of("text"), false));
        return new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                return ToolResult.success(name, String.valueOf(arguments.values().get("text")));
            }
        };
    }

    private static final class TestRegistry implements ToolRegistry {
        private final Map<String, ToolRegistration> registrations = new LinkedHashMap<>();
        TestRegistry(Collection<AgentTool> tools) { tools.forEach(tool -> registrations.put(tool.definition().name(), new ToolRegistration(tool, true))); }
        @Override public void register(ToolRegistration registration) { registrations.put(registration.definition().name(), registration); }
        @Override public ToolLookup lookup(String name) {
            ToolRegistration registration = registrations.get(name);
            return registration == null ? ToolLookup.unknown() : new ToolLookup(
                    registration.enabled() ? com.agentflow.core.tool.ToolAvailability.ENABLED : com.agentflow.core.tool.ToolAvailability.DISABLED, registration);
        }
        @Override public List<ToolDefinition> enabledDefinitions() {
            return registrations.values().stream().filter(ToolRegistration::enabled).map(ToolRegistration::definition).toList();
        }
    }
}
