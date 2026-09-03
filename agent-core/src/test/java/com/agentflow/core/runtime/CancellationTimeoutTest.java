package com.agentflow.core.runtime;

import com.agentflow.core.AgentEvent;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.model.AgentModelClient;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ToolCallDecision;
import com.agentflow.core.tool.ToolArguments;
import com.agentflow.core.tool.ToolCall;
import com.agentflow.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CancellationTimeoutTest {
    @Test
    void entryCancellationPreventsAnyModelOrToolAction() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModelClient model = request -> { modelCalls.incrementAndGet(); return new FinalAnswerDecision("d", "no", TokenUsage.empty()); };
        java.util.List<com.agentflow.core.AgentEvent> events = new java.util.ArrayList<>();
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), events::add,
                        new AgentRunOptions(new ExecutionBudget(3, Duration.ofSeconds(5), 10, 10), () -> true));
        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(TerminationReason.CANCELLED, result.terminationReason());
        assertEquals(0, modelCalls.get());
        assertEquals(1, events.stream().filter(com.agentflow.core.AgentEvent::terminal).count());
    }

    @Test
    void cancellationAfterModelWinsBeforeToolStarts() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger executions = new AtomicInteger();
        var tool = RuntimeTestSupport.tool("echo", (args, context) -> { executions.incrementAndGet(); return ToolResult.success("echo", "ok"); });
        AgentModelClient model = request -> {
            cancelled.set(true);
            return new ToolCallDecision("d1", new ToolCall("c1", "echo", new ToolArguments(Map.of())), TokenUsage.empty());
        };
        AgentResult result = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(tool), step -> { })
                .run(new AgentRequest("t", "s", "u", "input"), event -> { },
                        new AgentRunOptions(new ExecutionBudget(3, Duration.ofSeconds(5), 10, 10), cancelled::get));
        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(0, executions.get());
    }

    @Test
    void injectedClockTimeoutAndCancellationPriorityAreDeterministic() {
        AtomicInteger now = new AtomicInteger();
        AgentModelClient model = request -> { now.set(11); return new FinalAnswerDecision("d", "late", TokenUsage.empty()); };
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, RuntimeTestSupport.registry(), step -> { },
                new TimeSource() { @Override public long nanoTime() { return now.get(); } });
        AgentResult timeout = runtime.run(new AgentRequest("t", "s", "u", "input"), event -> { },
                new AgentRunOptions(new ExecutionBudget(3, Duration.ofNanos(10), 10, 10), CancellationSignal.NONE));
        assertEquals(RunStatus.TIMED_OUT, timeout.status());

        AgentResult cancelled = runtime.run(new AgentRequest("t2", "s", "u", "input"), event -> { },
                new AgentRunOptions(new ExecutionBudget(3, Duration.ZERO, 10, 10), () -> true));
        assertEquals(RunStatus.CANCELLED, cancelled.status());
    }
}
