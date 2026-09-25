package com.agentflow.core.runtime;

import com.agentflow.core.*;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.context.ContextSeed;
import com.agentflow.core.model.*;
import com.agentflow.core.rag.*;
import com.agentflow.core.tool.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCitationTest {
    @Test
    void conflictingSourcesRetainTheRetrievalErrorCodeAndNeverReachAnotherModelCall() throws Exception {
        var first = payload();
        var other = new RetrievalPayload("f".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
        var calls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        var tool = RuntimeTestSupport.tool("knowledge.search", (arguments, context) ->
                ToolResult.retrieval("knowledge.search", toolCalls.getAndIncrement() == 0 ? first : other));
        var runtime = RuntimeTestSupport.runtime(request -> {
            int number = calls.incrementAndGet();
            return new ToolCallDecision("d" + number, new ToolCall("c" + number, "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty());
        }, RuntimeTestSupport.registry(tool), null);
        var result = runtime.run(new AgentRequest("t", "s", "u", "question"), null, null);
        assertEquals(TerminationReason.TOOL_ERROR, result.terminationReason());
        assertTrue(result.steps().stream().anyMatch(step -> "RAG_SOURCE_INVALID".equals(step.errorCode())));
        assertEquals(2, calls.get());
        assertTrue(result.citations().isEmpty());
    }
    @Test
    void bindsDeliveredSourceBeforeFinalSuccessAndKeepsPublicTraceFreeOfExcerpt() throws Exception {
        var calls = new AtomicInteger();
        var payload = payload();
        AgentModelClient model = request -> {
            if (calls.getAndIncrement() == 0) {
                return new ToolCallDecision("d1", new ToolCall("c", "knowledge.search", new ToolArguments(Map.of())), TokenUsage.empty());
            }
            assertTrue(request.messages().stream().anyMatch(m -> m.role().equals("tool") && m.content().contains("source [S1]")));
            return new FinalAnswerDecision("d2", "Use the source [S1].", TokenUsage.empty());
        };
        var runtime = runtime(model, payload);
        var result = runtime.run(new AgentRequest("t", "s", "u", "question", ContextSeed.empty(), true), null, null);
        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(1, result.citations().size());
        assertEquals("unique private excerpt", result.citations().get(0).excerpt());
        assertFalse(result.steps().toString().contains("unique private excerpt"));
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsUnknownCitationAndMissingRequiredEvidenceWithoutFinalSuccessOrRetry() throws Exception {
        for (String answer : List.of("unknown [S99]", "no source")) {
            var calls = new AtomicInteger();
            var runtime = runtime(request -> { calls.incrementAndGet(); return new FinalAnswerDecision("d", answer, TokenUsage.empty()); }, payload());
            var result = runtime.run(new AgentRequest("t", "s", "u", "question", ContextSeed.empty(), true), null, null);
            assertEquals(RunStatus.FAILED, result.status());
            assertEquals(answer.contains("S99") ? "CITATION_INVALID" : "INSUFFICIENT_EVIDENCE", result.terminationReason().name());
            assertNull(result.finalAnswer());
            assertTrue(result.citations().isEmpty());
            assertEquals(1, calls.get());
            assertTrue(result.steps().stream().noneMatch(step -> step.stepType() == AgentStepType.FINAL));
        }
    }

    private DefaultAgentRuntime runtime(AgentModelClient model, RetrievalPayload payload) {
        var tool = RuntimeTestSupport.tool("knowledge.search", (arguments, context) -> ToolResult.retrieval("knowledge.search", payload));
        return RuntimeTestSupport.runtime(model, RuntimeTestSupport.registry(tool), null);
    }

    static RetrievalPayload payload() throws Exception {
        String text = "unique private excerpt";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        var hit = new RetrievedChunk("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), hash,
                "java.md", "Java", 0, text.length(), text);
        return new RetrievalPayload(hit.snapshotId(), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NONE, false, 0, List.of(hit));
    }
}
