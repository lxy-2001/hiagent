package com.agentflow.core.runtime;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetRuntimeTest {
    @Test
    void allowsFinalAtIterationBoundaryButDoesNotStartNextModelAfterToolBoundary() {
        AtomicInteger toolExecutions = new AtomicInteger();
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> {
            toolExecutions.incrementAndGet();
            return ToolResult.success("echo", "ok");
        });
        var registry = RuntimeTestSupport.registry(tool);
        AgentModelClient finalModel = request -> new FinalAnswerDecision("final", "done", TokenUsage.empty());
        AgentResult finalResult = new DefaultAgentRuntime(finalModel, registry, step -> { })
                .run(new AgentRequest("t1", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(1, Duration.ofSeconds(5), 100, 100),
                                com.agentflow.core.cancel.CancellationSignal.NONE));
        assertEquals(RunStatus.SUCCEEDED, finalResult.status());

        AtomicInteger modelCalls = new AtomicInteger();
        AgentModelClient toolModel = request -> {
            modelCalls.incrementAndGet();
            return new ToolCallDecision("d" + request.iteration(),
                    new ToolCall("c" + request.iteration(), "echo", new ToolArguments(Map.of())),
                    TokenUsage.empty());
        };
        AgentResult toolResult = new DefaultAgentRuntime(toolModel, registry, step -> { })
                .run(new AgentRequest("t2", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(1, Duration.ofSeconds(5), 100, 100),
                                com.agentflow.core.cancel.CancellationSignal.NONE));
        assertEquals(RunStatus.BUDGET_EXCEEDED, toolResult.status());
        assertEquals(TerminationReason.BUDGET_EXCEEDED, toolResult.terminationReason());
        assertEquals(1, modelCalls.get());
        assertEquals(1, toolExecutions.get());
    }

    @Test
    void exactTokenBoundaryAllowsCurrentFinalButBlocksFollowingModelAction() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        List<Integer> requestedCompletionBudgets = new ArrayList<>();
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> {
            toolExecutions.incrementAndGet();
            return ToolResult.success("echo", "ok");
        });
        AgentModelClient model = request -> {
            modelCalls.incrementAndGet();
            requestedCompletionBudgets.add(request.maxCompletionTokens());
            return new ToolCallDecision("d" + request.iteration(),
                    new ToolCall("c" + request.iteration(), "echo", new ToolArguments(Map.of())),
                    new TokenUsage(2, 3, 5));
        };

        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t-exact", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(4, Duration.ofSeconds(5), 2, 3),
                                com.agentflow.core.cancel.CancellationSignal.NONE));

        assertEquals(RunStatus.BUDGET_EXCEEDED, result.status());
        assertEquals(1, modelCalls.get());
        assertEquals(1, toolExecutions.get());
        assertEquals(List.of(3), requestedCompletionBudgets);
    }

    @Test
    void zeroTokenBudgetStopsBeforeStartingTheModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModelClient model = request -> {
            modelCalls.incrementAndGet();
            return new FinalAnswerDecision("d", "unexpected", TokenUsage.empty());
        };

        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(), step -> { })
                .run(new AgentRequest("t-zero", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(4, Duration.ofSeconds(5), 0, 0),
                                com.agentflow.core.cancel.CancellationSignal.NONE));

        assertEquals(RunStatus.BUDGET_EXCEEDED, result.status());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void responseTokenOverageRetainsModelStepButSkipsAction() {
        AtomicInteger executions = new AtomicInteger();
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> {
            executions.incrementAndGet();
            return ToolResult.success("echo", "ok");
        });
        AgentModelClient model = request -> new ToolCallDecision("d1",
                new ToolCall("c1", "echo", new ToolArguments(Map.of())),
                new TokenUsage(6, 0, 6));
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(3, Duration.ofSeconds(5), 5, 10),
                                com.agentflow.core.cancel.CancellationSignal.NONE));

        assertEquals(RunStatus.BUDGET_EXCEEDED, result.status());
        assertEquals(0, executions.get());
        assertTrue(result.steps().stream().anyMatch(step -> step.stepType() == com.agentflow.core.AgentStepType.MODEL_DECISION));
    }

    @Test
    void longAccumulatorNeverWrapsNegative() {
        AtomicInteger calls = new AtomicInteger();
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> ToolResult.success("echo", "ok"));
        AgentModelClient model = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new ToolCallDecision("d1", new ToolCall("c1", "echo", new ToolArguments(Map.of())),
                        new TokenUsage(Integer.MAX_VALUE, 0, Integer.MAX_VALUE));
            }
            return new FinalAnswerDecision("d2", "too late", new TokenUsage(1, 0, 1));
        };
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(3, Duration.ofSeconds(5), Integer.MAX_VALUE, Integer.MAX_VALUE),
                                com.agentflow.core.cancel.CancellationSignal.NONE));
        assertEquals(RunStatus.BUDGET_EXCEEDED, result.status());
        assertTrue(result.usage().promptTokens() >= 0);
        assertTrue(result.usage().totalTokens() >= 0);
    }
}
