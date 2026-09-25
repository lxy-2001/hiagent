package com.agentflow.web.autoconfigure;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.DefaultAgentRuntime;
import com.agentflow.core.step.StepRecorder;
import com.agentflow.core.tool.AgentTool;
import com.agentflow.core.tool.ParameterSpec;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolContext;
import com.agentflow.core.tool.ToolDefinition;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.tool.ToolResult;
import com.agentflow.core.tool.ToolSchema;
import com.agentflow.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web module smoke test for the Core Runtime. Detailed loop semantics live in agent-core tests.
 */
class DefaultAgentRuntimeTest {

    @Test
    void webModuleCanUseCoreRuntimeWithStructuredToolDecision() {
        List<AgentStepRecord> recorded = new ArrayList<>();
        AgentTool tool = new EchoTool();
        ToolRegistry registry = new InMemoryToolRegistry(List.of(tool));
        AgentModelClient model = request -> request.iteration() == 1
                ? new com.agentflow.core.model.ToolCallDecision("decision-1",
                new com.agentflow.core.tool.ToolCall("call-1", "echo",
                        new ToolArguments(Map.of("input", "hello"))), TokenUsage.empty())
                : new FinalAnswerDecision("decision-2", "done", TokenUsage.empty());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, registry, new com.agentflow.core.tool.DefaultToolExecutor(registry), recorded::add, null,
                com.agentflow.core.runtime.TimeSource.system(), new com.agentflow.core.context.ContextAssembler(com.agentflow.core.context.ContextPolicy.defaults(),new com.agentflow.core.context.Utf8TokenEstimator(),new com.agentflow.core.context.ContextTextPolicy()),
                com.agentflow.core.tool.ToolExecutionPolicy.rules(Map.of("echo",new com.agentflow.core.tool.ToolPolicyDecision(
                        com.agentflow.core.tool.ToolPolicyDecision.Action.ALLOW,com.agentflow.core.tool.RiskLevel.LOW,
                        com.agentflow.core.tool.ToolPolicyDecision.Effect.READ_ONLY,"Echo fixture",Set.of()))));

        AgentResult result = runtime.run(new AgentRequest("task-1", "session-1", "user-1", "hello"),
                AgentEventSink.NOOP);

        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        assertThat(recorded).extracting(AgentStepRecord::stepType)
                .contains(AgentStepType.MODEL_DECISION, AgentStepType.TOOL_CALL, AgentStepType.TOOL_RESULT,
                        AgentStepType.FINAL, AgentStepType.TERMINATION);
    }

    private static final class EchoTool implements AgentTool {
        private static final ToolDefinition DEFINITION = new ToolDefinition(
                "echo", "Echo text", RiskLevel.LOW,
                new ToolSchema(Map.of("input", ParameterSpec.requiredString(64)), Set.of("input"), false));

        @Override
        public ToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public ToolResult execute(ToolArguments arguments, ToolContext context) {
            return ToolResult.success(DEFINITION.name(), arguments.values().get("input").toString());
        }
    }
}
