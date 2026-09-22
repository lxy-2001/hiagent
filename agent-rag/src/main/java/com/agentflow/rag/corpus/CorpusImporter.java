package com.agentflow.rag.corpus;

import com.agentflow.core.model.DeadlineAwareEmbeddingClient;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.qdrant.VectorIndex;
import com.agentflow.core.model.EmbeddingCallOptions;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public final class CorpusImporter {
    public record Result(String outcome, String snapshotId, boolean reused, String errorCode, String warningCode) { }
    private final CorpusSnapshotStore store;
    private final DeadlineAwareEmbeddingClient embedding;
    private final VectorIndex vectors;

    public CorpusImporter(CorpusSnapshotStore store, DeadlineAwareEmbeddingClient embedding, VectorIndex vectors) {
        this.store = Objects.requireNonNull(store);
        this.embedding = Objects.requireNonNull(embedding);
        this.vectors = Objects.requireNonNull(vectors);
    }

    public Result importCorpus(CorpusManifest manifest, ToolExecutionControl parent) {
        var control = parent.child(Duration.ofMinutes(10));
        String stage = "STORAGE_FAILURE";
        boolean activationAttempted = false;
        try {
            control.checkActive();
            var active = store.active();
            String collection = store.collectionName(manifest.snapshotId());
            if (active != null && active.snapshotId().equals(manifest.snapshotId())) {
                store.loadActive(manifest.profile());
                return new Result("SUCCESS", manifest.snapshotId(), true, null, null);
            }
            if (store.preparation() == null) {
                // Read-only preflight cannot claim an unregistered remote collection on retry.
                stage = "VECTOR_INDEX_FAILED";
                if (vectors.collectionExists(collection, control.child(Duration.ofSeconds(5)))) {
                    throw new IllegalStateException("VECTOR_INDEX_FAILED");
                }
            }
            stage = "STORAGE_FAILURE";
            store.prepare(manifest);
            control.checkActive();
            stage = "VECTOR_INDEX_FAILED";
            vectors.ensureCollection(collection, manifest.profile().dimensions(), control.child(Duration.ofSeconds(5)));
            var chunks = manifest.documents().stream().flatMap(document -> document.chunks().stream()).toList();
            // Conservative JSON bound for finite doubles plus point metadata, always below 1 MiB.
            int batchLimit = Math.min(64, 1_048_576 / (manifest.profile().dimensions() * 26 + 1024));
            for (int offset = 0; offset < chunks.size(); offset += batchLimit) {
                var batch = new ArrayList<VectorIndex.VectorPoint>();
                for (var chunk : chunks.subList(offset, Math.min(chunks.size(), offset + batchLimit))) {
                    control.checkActive();
                    stage = "EMBEDDING_FAILED";
                    var call = control.child(Duration.ofSeconds(5));
                    var vector = embedding.embed(chunk.text(), new EmbeddingCallOptions(call.remainingTime(), call.cancellationSignal()));
                    call.checkActive();
                    validateVector(vector, manifest.profile().dimensions());
                    batch.add(new VectorIndex.VectorPoint(CorpusManifest.pointId(chunk.chunkId()), vector,
                            manifest.snapshotId(), chunk.chunkId(), chunk.contentHash()));
                }
                stage = "VECTOR_INDEX_FAILED";
                control.checkActive();
                vectors.upsert(collection, batch, control.child(Duration.ofSeconds(5)));
            }
            control.checkActive();
            if (vectors.countExact(collection, control.child(Duration.ofSeconds(5))) != chunks.size()) {
                throw new IllegalStateException("VECTOR_INDEX_FAILED");
            }
            for (int offset = 0; offset < chunks.size(); offset += 64) {
                var batch = chunks.subList(offset, Math.min(chunks.size(), offset + 64));
                var expected = new HashMap<java.util.UUID, DocumentChunker.Chunk>();
                batch.forEach(chunk -> expected.put(CorpusManifest.pointId(chunk.chunkId()), chunk));
                var points = vectors.readPoints(collection, List.copyOf(expected.keySet()), control.child(Duration.ofSeconds(5)));
                if (points.size() != batch.size()) { throw new IllegalStateException("VECTOR_INDEX_FAILED"); }
                for (var point : points) {
                    var chunk = expected.remove(point.pointId());
                    if (chunk == null || !manifest.snapshotId().equals(point.snapshotId())
                            || !chunk.chunkId().equals(point.chunkId()) || !chunk.contentHash().equals(point.contentHash())) {
                        throw new IllegalStateException("VECTOR_INDEX_FAILED");
                    }
                }
                control.checkActive();
            }
            stage = "STORAGE_FAILURE";
            store.complete(manifest);
            control.checkActive();
            activationAttempted = true;
            store.activate(manifest.snapshotId());
            return new Result("SUCCESS", manifest.snapshotId(), false, null, null);
        } catch (RuntimeException error) {
            if (activationAttempted) {
                try {
                    var active = store.active();
                    if (active != null && manifest.snapshotId().equals(active.snapshotId())) {
                        store.loadActive(manifest.profile());
                        return new Result("SUCCESS", manifest.snapshotId(), false, null, "COMMIT_METADATA_PENDING");
                    }
                } catch (RuntimeException unreadable) {
                    return new Result("INDETERMINATE", manifest.snapshotId(), false, "STORAGE_FAILURE", null);
                }
            }
            String code = error instanceof ToolExecutionControl.ExecutionTimedOutException
                    || control.remainingTime().isZero() ? "IMPORT_TIMEOUT" : stage;
            if (error.getMessage() != null && List.of("STALE_PREPARATION", "RETIRED_LIMIT", "ARCHIVE_LIMIT", "ATOMIC_ACTIVATION_UNSUPPORTED",
                    "INDEX_PROFILE_MISMATCH").contains(error.getMessage())) { code = error.getMessage(); }
            return new Result("FAILED", manifest.snapshotId(), false, code, null);
        }
    }

    public void cleanupPrepared(ToolExecutionControl control) {
        var prepared = store.preparation();
        if (prepared != null) { cleanup(prepared.snapshotId(), control); }
    }
    public void cleanupRetired(ToolExecutionControl control) {
        for (var pointer : store.retired()) { cleanup(pointer.snapshotId(), control); }
    }
    private void cleanup(String snapshotId, ToolExecutionControl control) {
        control.checkActive();
        var active = store.active();
        if (active != null && active.snapshotId().equals(snapshotId)) { throw new IllegalStateException("STORAGE_FAILURE"); }
        vectors.deleteOwnedCollection(store.collectionName(snapshotId), control.child(Duration.ofSeconds(5)));
        control.checkActive();
        store.removeRegistered(snapshotId);
    }
    private static void validateVector(List<Double> vector, int dimensions) {
        if (vector == null || vector.size() != dimensions || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v))
                || vector.stream().allMatch(v -> v == 0)) { throw new IllegalStateException("EMBEDDING_FAILED"); }
    }
}
