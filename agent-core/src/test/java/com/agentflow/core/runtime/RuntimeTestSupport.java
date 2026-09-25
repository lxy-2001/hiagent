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

    // Existing runtime tests explicitly permit only the tools supplied by their fixture registry.
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.step.StepRecorder recorder) {
        return runtime(model,registry,new com.agentflow.core.tool.DefaultToolExecutor(registry),recorder,null,TimeSource.system());
    }
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.step.StepRecorder recorder,TimeSource time) {
        return runtime(model,registry,new com.agentflow.core.tool.DefaultToolExecutor(registry),recorder,null,time);
    }
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.tool.ToolExecutor executor,com.agentflow.core.step.StepRecorder recorder) {
        return runtime(model,registry,executor,recorder,null,TimeSource.system());
    }
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.tool.ToolExecutor executor,com.agentflow.core.step.StepRecorder recorder,
            com.agentflow.core.tool.ToolResultNormalizer normalizer) {
        return runtime(model,registry,executor,recorder,normalizer,TimeSource.system());
    }
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.tool.ToolExecutor executor,com.agentflow.core.step.StepRecorder recorder,
            com.agentflow.core.tool.ToolResultNormalizer normalizer,TimeSource time) {
        return runtime(model,registry,executor,recorder,normalizer,time,new com.agentflow.core.context.ContextAssembler(
                com.agentflow.core.context.ContextPolicy.defaults(),new com.agentflow.core.context.Utf8TokenEstimator(),
                new com.agentflow.core.context.ContextTextPolicy()));
    }
    static DefaultAgentRuntime runtime(com.agentflow.core.model.AgentModelClient model,ToolRegistry registry,
            com.agentflow.core.tool.ToolExecutor executor,com.agentflow.core.step.StepRecorder recorder,
            com.agentflow.core.tool.ToolResultNormalizer normalizer,TimeSource time,com.agentflow.core.context.ContextAssembler assembler) {
        var rules=new java.util.HashMap<String,com.agentflow.core.tool.ToolPolicyDecision>();
        for(var definition:registry.enabledDefinitions()) rules.put(definition.name(),new com.agentflow.core.tool.ToolPolicyDecision(
                com.agentflow.core.tool.ToolPolicyDecision.Action.ALLOW,com.agentflow.core.tool.RiskLevel.LOW,
                com.agentflow.core.tool.ToolPolicyDecision.Effect.READ_ONLY,"Run fixture",java.util.Set.of()));
        return new DefaultAgentRuntime(model,registry,executor,recorder,normalizer,time,assembler,com.agentflow.core.tool.ToolExecutionPolicy.rules(rules));
    }
}
