package com.agentflow.core.rag;

public record Citation(String id, String snapshotId, String docId, String documentVersion, String chunkId,
                       String contentHash, String sourcePath, String title, int start, int end, String excerpt) {
    public Citation {
        if (id == null || !id.matches("S(?:[1-9]|[12][0-9]|3[0-2])")) {
            throw new IllegalArgumentException("citation id must be S1..S32");
        }
        new RetrievedChunk(snapshotId, docId, documentVersion, chunkId, contentHash, sourcePath, title, start, end, excerpt);
    }
}
