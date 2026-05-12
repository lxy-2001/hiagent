package com.agentflow.core.rag;

public record RagDocument(
        String id,
        String title,
        String content,
        double score
) {
}
