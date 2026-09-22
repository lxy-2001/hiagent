package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.model.*;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalTracePrivacyTest {
    @Test
    void modelExceptionCannotEchoDeliveredEvidenceIntoEvents() throws Exception {
        var calls = new AtomicInteger();
        var payload = RuntimeCitationTest.payload();
        var tool = RuntimeTestSupport.tool("knowledge.search", (arguments, context) -> ToolResult.retrieval("knowledge.search", payload));
        var runtime = new DefaultAgentRuntime(request -> {
            if (calls.getAndIncrement() == 0) {
                return new ToolCallDecision("d", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty());
            }
            throw new IllegalStateException("provider echoed unique private excerpt");
        }, RuntimeTestSupport.registry(tool), null);
        var events = new java.util.ArrayList<AgentEvent>();
        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), events::add, null);
        assertEquals(TerminationReason.MODEL_ERROR, result.terminationReason());
        assertFalse(result.steps().toString().contains("unique private excerpt"));
        assertFalse(events.toString().contains("unique private excerpt"));
    }

    @Test
    void queryAndExceptionBodyAreNotPublishedAndFinalDecisionContainsOnlyMetadata() {
        var calls = new AtomicInteger();
        var tool = new AgentTool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("knowledge.search", "search", RiskLevel.LOW,
                    new ToolSchema(Map.of("query", ParameterSpec.requiredString()), Set.of("query"), false)); }
            @Override public ToolResult execute(ToolArguments arguments, ToolContext context) { throw new IllegalStateException("private excerpt and query"); }
        };
        var events = new java.util.ArrayList<AgentEvent>();
        var runtime = new DefaultAgentRuntime(request -> {
            calls.incrementAndGet();
            return new ToolCallDecision("d", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of("query", "sensitive query"))), TokenUsage.empty());
        }, RuntimeTestSupport.registry(tool), null);
        var result = runtime.run(new AgentRequest("t", "s", "u", "input"), events::add, null);
        assertFalse(result.steps().toString().contains("sensitive query"));
        assertFalse(result.steps().toString().contains("private excerpt"));
        assertFalse(events.toString().contains("private excerpt"));
        assertEquals(1, calls.get());
        var finalRuntime = new DefaultAgentRuntime(request -> new FinalAnswerDecision("final", "unvalidated [S99]", TokenUsage.empty()), RuntimeTestSupport.registry(), null);
        var rejected = finalRuntime.run(new AgentRequest("other", "s", "u", "input"), null, null);
        assertFalse(rejected.steps().toString().contains("unvalidated"));
        assertEquals(TerminationReason.CITATION_INVALID, rejected.terminationReason());
    }
}
