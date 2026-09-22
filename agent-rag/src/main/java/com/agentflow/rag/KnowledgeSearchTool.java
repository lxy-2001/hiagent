package com.agentflow.rag;

import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.rag.RetrievalRequest;
import com.agentflow.core.tool.*;
import java.util.Map;
import java.util.Set;

public final class KnowledgeSearchTool implements AgentTool {
    private static final ToolDefinition DEFINITION = new ToolDefinition("knowledge.search", "Search public project and Java documentation",
            RiskLevel.LOW, new ToolSchema(Map.of("query", ParameterSpec.requiredString(512), "topK",
            new ParameterSpec(ValueType.INTEGER, false, false, "Maximum sources", null, null, 1L, 8L, null, null, Set.of(), null)), Set.of("query"), false));
    private final RagRetriever retriever;
    public KnowledgeSearchTool(RagRetriever retriever) {
        if (retriever == null || !retriever.supportsEvidence()) { throw new IllegalArgumentException("RAG_CONFIGURATION_INVALID"); }
        this.retriever = retriever;
    }
    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public ToolResult execute(ToolArguments arguments, ToolContext context) {
        var validation = DEFINITION.schema().validate(arguments);
        if (!validation.valid()) { return failure("INVALID_TOOL_ARGUMENTS"); }
        try {
            String query = ((String) arguments.values().get("query")).strip()
                    .replaceAll("(?i)([\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?)[^,;\\s\"'}]+", "$1[redacted]")
                    .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]");
            int topK = ((Number) arguments.values().getOrDefault("topK", 5)).intValue();
            var request = new RetrievalRequest(query, topK);
            var payload = retriever.retrieve(request, context.control());
            return ToolResult.retrieval(DEFINITION.name(), payload);
        } catch (java.util.concurrent.CancellationException cancelled) {
            return failure("RAG_CANCELLED");
        } catch (com.agentflow.core.runtime.ToolExecutionControl.ExecutionTimedOutException timeout) {
            return failure("RAG_TIMEOUT");
        } catch (IllegalArgumentException invalid) {
            return failure("INVALID_TOOL_ARGUMENTS");
        } catch (RuntimeException error) {
            String code = error.getMessage();
            return failure(code != null && Set.of("RAG_CONFIGURATION_INVALID", "RAG_SOURCE_INVALID", "RAG_TIMEOUT",
                    "RAG_EMBEDDING_UNAVAILABLE", "RAG_VECTOR_UNAVAILABLE").contains(code) ? code : "RAG_SOURCE_INVALID");
        }
    }

    private ToolResult failure(String code) { return ToolResult.failure(DEFINITION.name(), null, code, "knowledge retrieval failed"); }
}
