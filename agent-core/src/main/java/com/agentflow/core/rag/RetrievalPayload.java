package com.agentflow.core.rag;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

public record RetrievalPayload(String snapshotId, Mode mode, SemanticState semanticState,
                               KeywordState keywordState, String degradationCode, EmptyReason emptyReason,
                               boolean trimmed, long elapsedMillis, List<RetrievedChunk> hits) {
    public RetrievalPayload {
        RetrievedChunk.requireHash(snapshotId);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(semanticState, "semanticState");
        Objects.requireNonNull(keywordState, "keywordState");
        Objects.requireNonNull(emptyReason, "emptyReason");
        hits = List.copyOf(hits);
        if (hits.size() > 8 || elapsedMillis < 0
                || (hits.isEmpty() != (emptyReason != EmptyReason.NONE))
                || (emptyReason == EmptyReason.RESULT_LIMIT && !trimmed)) {
            throw new IllegalArgumentException("invalid retrieval bounds or empty state");
        }
        boolean hybrid = mode == Mode.HYBRID;
        if (hybrid ? semanticState != SemanticState.OK || degradationCode != null
                : semanticState != SemanticState.UNAVAILABLE
                    || !("RAG_VECTOR_UNAVAILABLE".equals(degradationCode) || "RAG_EMBEDDING_UNAVAILABLE".equals(degradationCode))) {
            throw new IllegalArgumentException("inconsistent retrieval mode");
        }
        var ids = new HashSet<String>();
        for (var hit : hits) {
            if (!snapshotId.equals(hit.snapshotId()) || !ids.add(hit.chunkId())) {
                throw new IllegalArgumentException("mixed snapshots or duplicate chunk");
            }
        }
    }
    public enum Mode { HYBRID, DEGRADED_KEYWORD }
    public enum SemanticState { OK, UNAVAILABLE }
    public enum KeywordState { OK }
    public enum EmptyReason { NONE, NO_MATCH, RESULT_LIMIT }
}
