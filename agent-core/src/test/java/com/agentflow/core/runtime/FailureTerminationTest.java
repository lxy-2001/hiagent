package com.agentflow.core.runtime;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FailureTerminationTest {
    @Test
    void modelExceptionIsARealFailureWithOneTerminalEvent() {
        List<AgentEvent> events = new ArrayList<>();
        AgentModelClient model = request -> { throw new IllegalStateException("apiKey=secret"); };
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), events::add);
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.MODEL_ERROR, result.terminationReason());
        assertNull(result.finalAnswer());
        assertEquals(1, events.stream().filter(AgentEvent::terminal).count());
    }

    @Test
    void toolExceptionRetainsCompletedDecisionAndDoesNotContinue() {
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> { throw new IllegalStateException("boom"); });
        AgentModelClient model = request -> new ToolCallDecision("d1",
                new ToolCall("c1", "echo", new ToolArguments(Map.of())), TokenUsage.empty());
        List<AgentEvent> events = new ArrayList<>();
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), events::add);
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.TOOL_ERROR, result.terminationReason());
        assertEquals(1, events.stream().filter(AgentEvent::terminal).count());
        assertEquals(1, result.steps().stream().filter(s -> s.stepType() == com.agentflow.core.AgentStepType.MODEL_DECISION).count());
    }

    @Test
    void malformedToolResultIsNotReportedAsSuccess() {
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> new ToolResult("echo", "",
                com.agentflow.core.tool.ToolResultStatus.SUCCESS, null, null, false, null));
        AgentModelClient model = request -> new ToolCallDecision("d1",
                new ToolCall("c1", "echo", new ToolArguments(Map.of())), TokenUsage.empty());
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), event -> { });
        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(TerminationReason.TOOL_RESULT_INVALID, result.terminationReason());
        assertNull(result.finalAnswer());
    }
}
