package com.agentflow.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolExecutorTest {
    @Test
    void validatesBeforeImplementationAndReturnsClassifiedFailures() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = tool(executions);
        InRegistry registry = new InRegistry(new ToolRegistration(tool, true));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ToolContext context = new ToolContext("t", "s", "u");

        ToolResult invalid = executor.execute(new ToolCall("c1", "echo", new ToolArguments(Map.of())), context);
        ToolResult unknown = executor.execute(new ToolCall("c2", "missing", new ToolArguments(Map.of())), context);

        assertEquals("INVALID_TOOL_ARGUMENTS", invalid.errorCode());
        assertEquals("UNKNOWN_TOOL", unknown.errorCode());
        assertEquals(0, executions.get());
    }

    @Test
    void normalizesSuccessfulResultAndConvertsImplementationException() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            private final ToolDefinition definition = DefaultToolExecutorTest.definition();
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
                executions.incrementAndGet();
                if ("boom".equals(arguments.values().get("text"))) throw new IllegalStateException("boom");
                return ToolResult.success("echo", "ok");
            }
        };
        InRegistry registry = new InRegistry(new ToolRegistration(tool, true));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);
        ToolContext context = new ToolContext("t", "s", "u");

        ToolResult success = executor.execute(new ToolCall("c1", "echo",
                new ToolArguments(Map.of("text", "yes"))), context);
        ToolResult failure = executor.execute(new ToolCall("c2", "echo",
                new ToolArguments(Map.of("text", "boom"))), context);

        assertEquals("c1", success.callId());
        assertEquals(ToolResultStatus.SUCCESS, success.status());
        assertEquals("TOOL_ERROR", failure.errorCode());
        assertEquals(2, executions.get());
    }

    @Test
    void nullNormalizerUsesSafeDefault() {
        AgentTool tool = new AgentTool() {
            private final ToolDefinition definition = DefaultToolExecutorTest.definition();

            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolArguments arguments, ToolContext context) {
                return ToolResult.success("echo", "apiKey=secret-value");
            }
        };
        InRegistry registry = new InRegistry(new ToolRegistration(tool, true));
        DefaultToolExecutor executor = new DefaultToolExecutor(registry, null);

        ToolResult result = executor.execute(new ToolCall("c-safe", "echo",
                new ToolArguments(Map.of("text", "hello"))),
                new ToolContext("t", "s", "u"));

        assertTrue(result.output().contains("[redacted]"));
        assertFalse(result.output().contains("secret-value"));
        assertEquals("c-safe", result.callId());
    }

    private static AgentTool tool(AtomicInteger executions) {
        return new AgentTool() {
            private final ToolDefinition definition = DefaultToolExecutorTest.definition();
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("echo", "ok");
            }
        };
    }

    private static ToolDefinition definition() {
        return new ToolDefinition("echo", "echo", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(32)), Set.of("text"), false));
    }

    private static final class InRegistry implements ToolRegistry {
        private final ToolRegistration registration;
        private InRegistry(ToolRegistration registration) { this.registration = registration; }
        @Override public void register(ToolRegistration registration) { }
        @Override public ToolLookup lookup(String name) {
            return registration.definition().name().equals(name)
                    ? new ToolLookup(ToolAvailability.ENABLED, registration) : ToolLookup.unknown();
        }
        @Override public List<ToolDefinition> enabledDefinitions() { return List.of(registration.definition()); }
    }
}
