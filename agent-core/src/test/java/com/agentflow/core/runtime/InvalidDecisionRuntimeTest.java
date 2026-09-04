package com.agentflow.core.runtime;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolResultNormalizer;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvalidDecisionRuntimeTest {

    @Test
    void nullDecisionFailsWithoutExecutingTool() {
        AtomicInteger executions = new AtomicInteger();
        DefaultAgentRuntime runtime = runtime(request -> null, executions);

        AgentResult result = runtime.run(new AgentRequest("t", "s", "u", "input"),
                event -> { });

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.INVALID_DECISION, result.terminationReason());
        assertNull(result.finalAnswer());
        assertEquals(0, executions.get());
    }

    @Test
    void directFinalDoesNotInvokeTool() {
        AtomicInteger executions = new AtomicInteger();
        DefaultAgentRuntime runtime = runtime(request ->
                new FinalAnswerDecision("final", "answer", TokenUsage.empty()), executions);

        AgentResult result = runtime.run(new AgentRequest("t", "s", "u", "input"), event -> { });

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(TerminationReason.COMPLETED, result.terminationReason());
        assertEquals("answer", result.finalAnswer());
        assertEquals(0, executions.get());
    }

    @Test
    void duplicateDecisionIdFailsBeforeASecondToolExecution() {
        AtomicInteger executions = new AtomicInteger();
        AgentModelClient model = request -> request.iteration() == 1
                ? new ToolCallDecision("same", new ToolCall("c1", "tool", new ToolArguments(Map.of())), TokenUsage.empty())
                : new FinalAnswerDecision("same", "should not finish", TokenUsage.empty());
        DefaultAgentRuntime runtime = runtime(model, executions);

        AgentResult result = runtime.run(new AgentRequest("t", "s", "u", "input"), event -> { });

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.INVALID_DECISION, result.terminationReason());
        assertEquals(1, executions.get());
    }

    private static DefaultAgentRuntime runtime(AgentModelClient model, AtomicInteger executions) {
        ToolRegistry registry = new EnabledRegistry();
        ToolExecutor executor = (call, context) -> {
            executions.incrementAndGet();
            return ToolResult.success(call.name(), "ok");
        };
        return new DefaultAgentRuntime(model, registry, executor, step -> { }, ToolResultNormalizer.IDENTITY);
    }

    private static final class EnabledRegistry implements ToolRegistry {
        private final AgentTool tool = new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition("tool", "test tool", RiskLevel.LOW,
                    new ToolSchema(Map.of(), java.util.Set.of(), false));
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                return ToolResult.success("tool", "ok");
            }
        };
        private final ToolRegistration registration = new ToolRegistration(tool, true);
        @Override public void register(ToolRegistration registration) { }
        @Override public ToolLookup lookup(String name) {
            return "tool".equals(name) ? new ToolLookup(ToolAvailability.ENABLED, registration) : ToolLookup.unknown();
        }
        @Override public List<ToolDefinition> enabledDefinitions() { return List.of(registration.definition()); }
    }
}
