package com.agentflow.core.rag;

public record RetrievalRequest(String query, int topK) {
    public RetrievalRequest {
        if (query == null || query.isBlank() || query.length() > 512 || topK < 1 || topK > 8) {
            throw new IllegalArgumentException("query must be nonblank and at most 512 characters; topK must be 1..8");
        }
    }
    public RetrievalRequest(String query) {
        this(query, 5);
    }
}
