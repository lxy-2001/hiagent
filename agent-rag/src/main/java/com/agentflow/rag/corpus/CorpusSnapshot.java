package com.agentflow.rag.corpus;

import com.agentflow.core.rag.RetrievedChunk;
import java.util.Map;

public record CorpusSnapshot(String snapshotId, String collectionName, CorpusManifest.IndexProfile profile,
                             Map<String, RetrievedChunk> chunks) {
    public CorpusSnapshot { chunks = Map.copyOf(chunks); }
}
