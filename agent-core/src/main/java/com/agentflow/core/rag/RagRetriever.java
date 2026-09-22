package com.agentflow.core.rag;

import java.util.List;

public interface RagRetriever {

    List<RagDocument> retrieve(String query, int limit);

    default RetrievalPayload retrieve(RetrievalRequest request,
                                      com.agentflow.core.runtime.ToolExecutionControl control) {
        throw new UnsupportedOperationException("retriever does not support source evidence");
    }

    default boolean supportsEvidence() {
        return false;
    }
}
