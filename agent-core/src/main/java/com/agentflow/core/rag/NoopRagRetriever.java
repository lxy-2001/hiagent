package com.agentflow.core.rag;

import java.util.List;

public class NoopRagRetriever implements RagRetriever {

    @Override
    public List<RagDocument> retrieve(String query, int limit) {
        return List.of();
    }
}
