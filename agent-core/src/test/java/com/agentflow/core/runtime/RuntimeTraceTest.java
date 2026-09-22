package com.agentflow.core.runtime;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolExecutor;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolRegistration;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolResultNormalizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTraceTest {

    @Test
    void stepsAndEventsAreMonotonicAndCorrelateToolCall() {
        AgentModelClient model = request -> request.iteration() == 1
                ? new ToolCallDecision("decision-1", new ToolCall("call-1", "echo",
                        new ToolArguments(Map.of())), TokenUsage.empty())
                : new FinalAnswerDecision("decision-2", "done", TokenUsage.empty());
        ToolRegistry registry = new Registry();
        ToolExecutor executor = (call, context) -> ToolResult.success(call.name(), "observed");
        List<AgentEvent> events = new ArrayList<>();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, registry, executor,
                step -> { }, ToolResultNormalizer.IDENTITY);

        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), events::add);

        List<Integer> stepNumbers = result.steps().stream().map(AgentStepRecord::stepNo).toList();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), stepNumbers);
        List<Long> eventSequences = events.stream().map(AgentEvent::sequence).toList();
        assertTrue(eventSequences.stream().allMatch(sequence -> sequence > 0));
        assertTrue(eventSequences.stream().allMatch(sequence -> eventSequences.indexOf(sequence) == eventSequences.lastIndexOf(sequence)));
        assertEquals(1, events.stream().filter(AgentEvent::terminal).count());
        assertEquals("call-1", events.stream().filter(event -> event.type() == AgentStepType.TOOL_CALL)
                .findFirst().orElseThrow().correlationId());
        String callId = result.steps().stream().filter(s -> s.callId() != null)
                .map(AgentStepRecord::callId).findFirst().orElseThrow();
        assertEquals("call-1", callId);
        assertEquals(AgentStepType.TERMINATION, result.steps().get(7).stepType());
    }

    @Test
    void traceRedactsSensitiveFieldsAndBoundsPayloads() {
        RuntimeTrace trace = new RuntimeTrace("t", step -> { }, event -> { });
        String longValue = "x".repeat(2_000);

        AgentStepRecord step = trace.success(AgentStepType.TOOL_CALL, "echo",
                "apiKey=secret-value " + longValue,
                "token: another-secret", 0, null, "d", "c", false);

        assertTrue(step.input().length() <= 1_024);
        assertTrue(step.output().length() <= 1_024);
        assertTrue(!step.input().contains("secret-value"));
        assertTrue(!step.output().contains("another-secret"));
    }

    @Test
    void observerFailuresDoNotDuplicateOrChangeTerminalOutcome() {
        AgentModelClient model = request -> new FinalAnswerDecision("d", "done", TokenUsage.empty());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, new Registry(),
                (call, context) -> ToolResult.success("echo", "unused"),
                step -> { throw new RuntimeException("recorder unavailable"); },
                ToolResultNormalizer.IDENTITY);
        List<AgentEvent> events = new ArrayList<>();

        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), event -> {
            events.add(event);
            throw new RuntimeException("client disconnected");
        });

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, events.stream().filter(AgentEvent::terminal).count());
    }

    private static final class Registry implements ToolRegistry {
        private final AgentTool tool = new AgentTool() {
            private final ToolDefinition definition = new ToolDefinition("echo", "echo", RiskLevel.LOW,
                    new ToolSchema(Map.of(), java.util.Set.of(), false));
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                return ToolResult.success("echo", "observed");
            }
        };
        private final ToolRegistration registration = new ToolRegistration(tool, true);
        @Override public void register(ToolRegistration registration) { }
        @Override public ToolLookup lookup(String name) {
            return "echo".equals(name) ? new ToolLookup(ToolAvailability.ENABLED, registration) : ToolLookup.unknown();
        }
        @Override public List<ToolDefinition> enabledDefinitions() { return List.of(registration.definition()); }
    }
}
