package com.agentflow.rag;

import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.rag.RagRetriever;

import java.util.List;

public class NoopRagRetriever implements RagRetriever {

    @Override
    public List<RagDocument> retrieve(String query, int limit) {
        return List.of();
    }
}
