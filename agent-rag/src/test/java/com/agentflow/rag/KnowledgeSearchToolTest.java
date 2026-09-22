package com.agentflow.rag;

import com.agentflow.core.rag.*;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.core.tool.*;
import com.agentflow.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class KnowledgeSearchToolTest {
    @Test
    void realRegistryValidatesArgumentsAndPreservesTypedPayload() {
        var received = new AtomicReference<RetrievalRequest>();
        var payload = new RetrievalPayload("a".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
        RagRetriever retriever = new RagRetriever() {
            @Override public boolean supportsEvidence() { return true; }
            @Override public List<RagDocument> retrieve(String query, int limit) { throw new AssertionError(); }
            @Override public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl control) {
                received.set(request); return payload;
            }
        };
        var executor = new DefaultToolExecutor(new InMemoryToolRegistry(List.of(new KnowledgeSearchTool(retriever))));
        var context = new ToolContext("t", "s", "u");
        var result = executor.execute(call(Map.of("query", " Java ")), context);
        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.retrievalPayload()).isEqualTo(payload);
        assertThat(received.get()).isEqualTo(new RetrievalRequest("Java", 5));
        for (var arguments : List.of(Map.of("query", "Java", "topK", 9), Map.of("query", "Java", "extra", true),
                Map.of("query", " "), Map.of("query", "x".repeat(513)))) {
            assertThat(executor.execute(call(arguments), context).status()).isEqualTo(ToolResultStatus.FAILED);
        }
    }

    @Test
    void refusesLegacyRetrieverAndDoesNotExposeFailureDetails() {
        assertThatThrownBy(() -> new KnowledgeSearchTool((q, n) -> List.of())).hasMessage("RAG_CONFIGURATION_INVALID");
        RagRetriever retriever = new RagRetriever() {
            @Override public boolean supportsEvidence() { return true; }
            @Override public List<RagDocument> retrieve(String query, int limit) { return List.of(); }
            @Override public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl control) {
                throw new IllegalStateException("private document body");
            }
        };
        var tool = new KnowledgeSearchTool(retriever);
        var result = tool.execute(new ToolArguments(Map.of("query", "private question")), new ToolContext("t", "s", "u"));
        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.diagnostic()).doesNotContain("private");
    }

    private ToolCall call(Map<String, ?> arguments) { return new ToolCall("c", "knowledge.search", new ToolArguments(arguments)); }
}
