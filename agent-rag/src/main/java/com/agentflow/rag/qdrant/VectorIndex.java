package com.agentflow.rag.qdrant;

import com.agentflow.core.runtime.ToolExecutionControl;
import java.util.List;
import java.util.UUID;

/** Protocol-neutral vector operations; source text is always loaded from the local snapshot. */
public interface VectorIndex {
    record VectorPoint(UUID pointId, List<Double> vector, String snapshotId, String chunkId, String contentHash) {
        public VectorPoint { vector = List.copyOf(vector); }
    }
    record StoredPoint(UUID pointId, String snapshotId, String chunkId, String contentHash) { }
    record VectorHit(UUID pointId, String snapshotId, String chunkId, String contentHash, double score) { }
    boolean collectionExists(String collection, ToolExecutionControl control);
    void ensureCollection(String collection, int dimensions, ToolExecutionControl control);
    void upsert(String collection, List<VectorPoint> points, ToolExecutionControl control);
    long countExact(String collection, ToolExecutionControl control);
    List<StoredPoint> readPoints(String collection, List<UUID> ids, ToolExecutionControl control);
    List<VectorHit> search(String collection, String snapshotId, List<Double> queryVector,
                           int limit, double threshold, ToolExecutionControl control);
    void deleteOwnedCollection(String collection, ToolExecutionControl control);
}
