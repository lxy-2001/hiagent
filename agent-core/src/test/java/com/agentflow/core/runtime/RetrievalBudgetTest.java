package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import com.agentflow.core.context.*;
import com.agentflow.core.cancel.CancellationSignal;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalBudgetTest {
    @Test
    void currentEvidenceChainOverflowDoesNotCallModelAgain() throws Exception {
        var calls = new AtomicInteger();
        var payload = RuntimeCitationTest.payload();
        var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("knowledge.search", (args, context) -> ToolResult.retrieval("knowledge.search", payload)));
        AgentModelClient model = request -> {
            calls.incrementAndGet();
            return new ToolCallDecision("d", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty());
        };
        var assembler = new ContextAssembler(new ContextPolicy("system", "test", 500), new Utf8TokenEstimator(), new ContextTextPolicy());
        var runtime = RuntimeTestSupport.runtime(model, registry, new DefaultToolExecutor(registry), null, null, TimeSource.system(), assembler);
        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), null,
                new AgentRunOptions(new ExecutionBudget(8, Duration.ofSeconds(30), 4096, 20), CancellationSignal.NONE));
        assertEquals(TerminationReason.CONTEXT_BUDGET_EXCEEDED, result.terminationReason());
        assertEquals(1, calls.get());
    }

    @Test
    void cancellationAfterToolPreventsNextDecisionAndUsesTheSameSignal() throws Exception {
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        var payload = RuntimeCitationTest.payload();
        var tool = RuntimeTestSupport.tool("knowledge.search", (args, context) -> {
            cancelled.set(true);
            assertTrue(context.control().isCancelled());
            return ToolResult.retrieval("knowledge.search", payload);
        });
        var runtime = RuntimeTestSupport.runtime(request -> {
            calls.incrementAndGet();
            return new ToolCallDecision("d", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty());
        }, RuntimeTestSupport.registry(tool), null);
        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), null,
                new AgentRunOptions(ExecutionBudget.defaults(), cancelled::get));
        assertEquals(TerminationReason.CANCELLED, result.terminationReason());
        assertEquals(1, calls.get());
    }

    @Test
    void customAssemblerRemovingEvidenceCannotAuthorizeCitation() throws Exception {
        var payload = RuntimeCitationTest.payload();
        var registry = RuntimeTestSupport.registry(RuntimeTestSupport.tool("knowledge.search", (args, context) -> ToolResult.retrieval("knowledge.search", payload)));
        var calls = new AtomicInteger();
        AgentModelClient model = request -> calls.getAndIncrement() == 0
                ? new ToolCallDecision("d1", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty())
                : new FinalAnswerDecision("d2", "answer [S1]", TokenUsage.empty());
        var assembler = new ContextAssembler(ContextPolicy.defaults(), new Utf8TokenEstimator(), new ContextTextPolicy()) {
            @Override public ContextAssembly assemble(AgentRequest request, List<ModelMessage> messages, List<ToolDefinition> tools, int iteration, int remaining) {
                var actual = super.assemble(request, messages, tools, iteration, remaining);
                if (iteration == 1 || !actual.ready()) { return actual; }
                var changed = actual.request().messages().stream().filter(message -> !message.role().equals("tool")).toList();
                return new ContextAssembly(new AgentModelRequest(request, changed, tools, iteration, remaining), null, actual.diagnostics());
            }
        };
        var result = RuntimeTestSupport.runtime(model, registry, new DefaultToolExecutor(registry), null, null, TimeSource.system(), assembler)
                .run(new AgentRequest("t", "s", "u", "input"), null, null);
        assertEquals(TerminationReason.CITATION_INVALID, result.terminationReason());
        assertTrue(result.citations().isEmpty());
    }
}
