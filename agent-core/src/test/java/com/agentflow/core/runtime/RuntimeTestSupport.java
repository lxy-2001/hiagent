package com.agentflow.core.runtime;

import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolAvailability;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolLookup;
import com.agentflow.core.tool.ToolRegistration;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuntimeTestSupport {
    private RuntimeTestSupport() { }

    static ToolDefinition definition(String name) {
        return new ToolDefinition(name, name + " tool", RiskLevel.LOW,
                new ToolSchema(Map.of(), Set.of(), false));
    }

    static AgentTool tool(String name, java.util.function.BiFunction<ToolArguments, ToolContext, ToolResult> action) {
        ToolDefinition definition = definition(name);
        return new AgentTool() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
                return action.apply(arguments, context);
            }
        };
    }

    static ToolRegistry registry(AgentTool... tools) {
        Map<String, ToolRegistration> registrations = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            registrations.put(tool.definition().name(), new ToolRegistration(tool, true));
        }
        return new ToolRegistry() {
            @Override public void register(ToolRegistration registration) {
                registrations.put(registration.definition().name(), registration);
            }
            @Override public ToolLookup lookup(String name) {
                ToolRegistration registration = registrations.get(name);
                return registration == null ? ToolLookup.unknown() : new ToolLookup(
                        registration.enabled() ? ToolAvailability.ENABLED : ToolAvailability.DISABLED, registration);
            }
            @Override public List<ToolDefinition> enabledDefinitions() {
                return registrations.values().stream().filter(ToolRegistration::enabled)
                        .map(ToolRegistration::definition).toList();
            }
        };
    }
}
