package com.agentflow.core.runtime;

import com.agentflow.core.AgentRequest;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.AgentModelRequest;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.model.ToolCallDecision;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentContextAssemblyTest {

    @Test
    void nextDecisionSeesOnlyConfirmedMessagesInStableOrder() {
        ToolDefinition definition = new ToolDefinition("echo", "echo", RiskLevel.LOW,
                new ToolSchema(Map.of("text", ParameterSpec.requiredString(32)), Set.of("text"), false));
        AgentTool tool = new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, com.agentflow.core.tool.ToolContext context) {
                return ToolResult.success("echo", "observed");
            }
        };
        ToolRegistry registry = new SingleRegistry(tool);
        List<AgentModelRequest> requests = new ArrayList<>();
        AgentModelClient model = request -> {
            requests.add(request);
            if (request.iteration() == 1) {
                assertEquals(List.of("system", "user"), request.messages().stream().map(ModelMessage::role).toList());
                return new ToolCallDecision("d1", new ToolCall("c1", "echo",
                        new ToolArguments(Map.of("text", "x"))), TokenUsage.empty());
            }
            assertEquals(List.of("system", "user", "assistant", "tool"),
                    request.messages().stream().map(ModelMessage::role).toList());
            assertEquals("observed", request.messages().get(3).content());
            assertEquals("echo", request.messages().get(2).name());
            return new FinalAnswerDecision("d2", "final", TokenUsage.empty());
        };
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, registry,
                (call, context) -> ToolResult.success(call.name(), "observed"), step -> { },
                ToolResultNormalizer.IDENTITY);

        runtime.run(new AgentRequest("t", "s", "u", "hello"), event -> { });

        assertEquals(2, requests.size());
        assertThrows(UnsupportedOperationException.class,
                () -> requests.get(1).messages().clear());
    }

    private static final class SingleRegistry implements ToolRegistry {
        private final ToolRegistration registration;
        SingleRegistry(AgentTool tool) { this.registration = new ToolRegistration(tool, true); }
        @Override public void register(ToolRegistration value) { }
        @Override public ToolLookup lookup(String name) {
            return registration.definition().name().equals(name) ?
                    new ToolLookup(com.agentflow.core.tool.ToolAvailability.ENABLED, registration) : ToolLookup.unknown();
        }
        @Override public List<ToolDefinition> enabledDefinitions() { return List.of(registration.definition()); }
    }
}
