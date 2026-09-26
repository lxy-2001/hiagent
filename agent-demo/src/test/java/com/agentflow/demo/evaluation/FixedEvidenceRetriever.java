package com.agentflow.demo.evaluation;

import com.agentflow.core.rag.*;
import com.agentflow.core.runtime.ToolExecutionControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class FixedEvidenceRetriever implements RagRetriever {
    private final String scenario;
    private final RetrievedChunk chunk;
    public FixedEvidenceRetriever(String scenario) {
        this.scenario = scenario;
        String excerpt = "Java interfaces define contracts between modules.";
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(excerpt.getBytes(StandardCharsets.UTF_8)));
            chunk = new RetrievedChunk("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), hash,
                    "java.md", "Java", 0, excerpt.length(), excerpt);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    RetrievedChunk chunk() { return chunk; }
    @Override public boolean supportsEvidence() { return true; }
    @Override public List<RagDocument> retrieve(String query, int limit) { throw new UnsupportedOperationException(); }
    @Override public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl control) {
        if (scenario.equals("retrieval-failure")) throw new IllegalStateException("RAG_SOURCE_INVALID");
        boolean empty = scenario.equals("empty-evidence");
        return new RetrievalPayload(chunk.snapshotId(), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                RetrievalPayload.KeywordState.OK, null, empty ? RetrievalPayload.EmptyReason.NO_MATCH : RetrievalPayload.EmptyReason.NONE,
                false, 0, empty ? List.of() : List.of(chunk));
    }
}
