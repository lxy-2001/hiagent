package com.agentflow.core.runtime;

import com.agentflow.core.AgentEventSink;
import com.agentflow.core.AgentRequest;
import com.agentflow.core.AgentResult;
import com.agentflow.core.AgentStepType;
import com.agentflow.core.context.ContextAssembler;
import com.agentflow.core.context.ContextPolicy;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.core.context.ContextTextPolicy;
import com.agentflow.core.context.ConversationTurn;
import com.agentflow.core.context.Utf8TokenEstimator;
import com.agentflow.core.model.FinalAnswerDecision;
import com.agentflow.core.model.ModelMessage;
import com.agentflow.core.support.ContextModelFixture;
import com.agentflow.core.chat.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerDecisionContextTest {
    @Test
    void growingToolResultPreventsSecondModelCall() {
        var tool = RuntimeTestSupport.tool("echo", (a, c) -> com.agentflow.core.tool.ToolResult.success("echo", "x".repeat(2000)));
        var registry = RuntimeTestSupport.registry(tool);
        var model = new ContextModelFixture(List.of(new com.agentflow.core.model.ToolCallDecision("d",
                new com.agentflow.core.tool.ToolCall("c", "echo", new com.agentflow.core.tool.ToolArguments(java.util.Map.of())), TokenUsage.empty())));
        var assembler = new ContextAssembler(new ContextPolicy("system", "test", 1000), new Utf8TokenEstimator(), new ContextTextPolicy());
        var runtime = RuntimeTestSupport.runtime(model, registry, new com.agentflow.core.tool.DefaultToolExecutor(registry), null,
                null, TimeSource.system(), assembler);
        var result = runtime.run(new AgentRequest("r", "s", "u", "now"), AgentEventSink.NOOP,
                new AgentRunOptions(new ExecutionBudget(8, java.time.Duration.ofSeconds(30), 4096, 100), com.agentflow.core.cancel.CancellationSignal.NONE));
        assertEquals(TerminationReason.CONTEXT_BUDGET_EXCEEDED, result.terminationReason());
        assertEquals(1, model.callCount());
        assertEquals(2, result.steps().stream().filter(step -> step.stepType() == AgentStepType.CONTEXT_ASSEMBLY).count());
    }

    @Test
    void cancellationOrTimeoutDuringContextObservationPreventsModelCall() {
        for (boolean cancel : List.of(false, true)) {
            var clock = new java.util.concurrent.atomic.AtomicLong();
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var model = new ContextModelFixture(List.of());
            var runtime = RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(), step -> {
                if (step.stepType() == AgentStepType.CONTEXT_ASSEMBLY) {
                    clock.set(java.time.Duration.ofSeconds(30).toNanos());
                    cancelled.set(cancel);
                }
            }, clock::get);
            var result = runtime.run(new AgentRequest("r", "s", "u", "now"), AgentEventSink.NOOP,
                    new AgentRunOptions(ExecutionBudget.defaults(), cancelled::get));
            assertEquals(cancel ? TerminationReason.CANCELLED : TerminationReason.TIMED_OUT, result.terminationReason());
            assertEquals(0, model.callCount());
        }
    }

    @Test
    void everyDecisionReassemblesSeedAndReservesRemainingCompletionBudget() {
        var tool = RuntimeTestSupport.tool("echo", (a, c) -> com.agentflow.core.tool.ToolResult.success("echo", "observation"));
        var model = new ContextModelFixture(List.of(new com.agentflow.core.model.ToolCallDecision("d1",
                new com.agentflow.core.tool.ToolCall("c", "echo", new com.agentflow.core.tool.ToolArguments(java.util.Map.of())), new TokenUsage(10, 20, 30)),
                new FinalAnswerDecision("d2", "done", new TokenUsage(4086, 2028, 6114))));
        var seed = new ContextSeed(1, List.of(new ConversationTurn("prior", 1, "question", "answer")), List.of(), false, 0);
        var runtime = RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(tool), null);
        var result = runtime.run(new AgentRequest("r", "s", "u", "now", seed), AgentEventSink.NOOP);
        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(2048, model.requests().get(0).maxCompletionTokens());
        assertEquals(2028, model.requests().get(1).maxCompletionTokens());
        for (var request : model.requests()) {
            assertEquals(1, request.messages().stream().filter(m -> m.content().equals("question")).count());
            assertEquals(1, request.messages().stream().filter(m -> m.content().equals("now")).count());
        }
        assertEquals(new TokenUsage(4096, 2048, 6144), result.usage());
    }

    @Test
    void defaultRuntimeUsesHistoryAndAddsContextStepWithoutDuplicatingCurrentInput() {
        var model = new ContextModelFixture(List.of(new FinalAnswerDecision("d", "done", TokenUsage.empty())));
        var seed = new ContextSeed(1, List.of(new ConversationTurn("prior", 1, "question", "answer")), List.of(), false, 0);
        var runtime = RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(), null);
        var result = runtime.run(new AgentRequest("current", "session", "user", "now", seed), AgentEventSink.NOOP);
        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(List.of("system", "user", "assistant", "user"), model.requests().get(0).messages()
                .stream().map(ModelMessage::role).toList());
        assertEquals(1, result.steps().stream().filter(step -> step.stepType().name().equals("CONTEXT_ASSEMBLY")).count());
        assertEquals(1, model.requests().get(0).messages().stream().filter(m -> m.content().equals("now")).count());
    }

    @Test
    void oversizedNecessaryInputStopsBeforeAnyModelCall() {
        var model = new ContextModelFixture(List.of());
        var runtime = RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(), null);
        // CJK content alone exceeds default 16384 UTF8-unit window.
        var result = runtime.run(new AgentRequest("r", "s", "u", "中".repeat(6000)), AgentEventSink.NOOP);
        assertEquals(RunStatus.BUDGET_EXCEEDED, result.status());
        assertEquals("CONTEXT_BUDGET_EXCEEDED", result.terminationReason().name());
        assertEquals(0, model.callCount());
    }
}
