package com.agentflow.rag.retrieval;

import com.agentflow.core.rag.*;
import com.agentflow.core.model.DeadlineAwareEmbeddingClient;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.corpus.CorpusSnapshot;
import com.agentflow.rag.qdrant.VectorIndex;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.ArrayList;
import java.util.stream.Collectors;
import com.agentflow.core.model.EmbeddingCallOptions;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.rag.corpus.CorpusManifest;

public final class HybridRagRetriever implements RagRetriever {
    private final CorpusSnapshot snapshot;
    private final DeadlineAwareEmbeddingClient embedding;
    private final VectorIndex vectors;
    private final boolean allowFallback;
    private final KeywordIndex keyword;
    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    public HybridRagRetriever(CorpusSnapshot snapshot, DeadlineAwareEmbeddingClient embedding, VectorIndex vectors,
                              boolean allowFallback) {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.embedding = Objects.requireNonNull(embedding);
        this.vectors = Objects.requireNonNull(vectors);
        this.allowFallback = allowFallback;
        if (snapshot.chunks().isEmpty() || snapshot.chunks().entrySet().stream().anyMatch(entry ->
                !entry.getKey().equals(entry.getValue().chunkId()) || !snapshot.snapshotId().equals(entry.getValue().snapshotId()))) {
            throw new IllegalStateException("RAG_SOURCE_INVALID");
        }
        keyword = new KeywordIndex(snapshot.chunks().entrySet().stream()
                .collect(Collectors.toMap(java.util.Map.Entry::getKey, entry -> entry.getValue().text())));
    }
    @Override public boolean supportsEvidence() { return true; }
    @Override public List<RagDocument> retrieve(String query, int limit) {
        return retrieve(new RetrievalRequest(query, limit), new ToolExecutionControl(CancellationSignal.NONE,
                TimeSource.system(), Duration.ofSeconds(5))).hits().stream()
                .map(hit -> new RagDocument(hit.chunkId(), hit.title(), hit.text(), 0)).toList();
    }

    @Override public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl parent) {
        long started = System.nanoTime();
        var control = parent.child(Duration.ofSeconds(5));
        check(control);
        var semanticControl = control.child(Duration.ofNanos(control.remainingTime().toNanos() / 5 * 4));
        String stage = "RAG_EMBEDDING_UNAVAILABLE";
        String degradation = null;
        List<ReciprocalRankFusion.Candidate> semantic = List.of();
        try {
            semanticControl.checkActive();
            var vector = embedding.embed(request.query(), new EmbeddingCallOptions(semanticControl.remainingTime(), semanticControl.cancellationSignal()));
            check(control);
            semanticControl.checkActive();
            if (vector == null || vector.size() != snapshot.profile().dimensions()
                    || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v)) || vector.stream().allMatch(v -> v == 0)) {
                throw new IllegalStateException("RAG_CONFIGURATION_INVALID");
            }
            stage = "RAG_VECTOR_UNAVAILABLE";
            var hits = vectors.search(snapshot.collectionName(), snapshot.snapshotId(), vector, 40, .35, semanticControl);
            check(control);
            semanticControl.checkActive();
            if (hits == null || hits.size() > 40) { throw new IllegalStateException("RAG_SOURCE_INVALID"); }
            var candidates = new ArrayList<ReciprocalRankFusion.Candidate>();
            for (var hit : hits) {
                var local = snapshot.chunks().get(hit.chunkId());
                if (local == null || !snapshot.snapshotId().equals(hit.snapshotId()) || !local.contentHash().equals(hit.contentHash())
                        || !CorpusManifest.pointId(hit.chunkId()).equals(hit.pointId()) || !Double.isFinite(hit.score())) {
                    throw new IllegalStateException("RAG_SOURCE_INVALID");
                }
                if (hit.score() >= .35) { candidates.add(new ReciprocalRankFusion.Candidate(hit.chunkId(), hit.score())); }
            }
            semantic = List.copyOf(candidates);
        } catch (RuntimeException failure) {
            check(control);
            if (failure instanceof java.util.concurrent.CancellationException) { throw failure; }
            if ("RAG_SOURCE_INVALID".equals(failure.getMessage()) || "RAG_CONFIGURATION_INVALID".equals(failure.getMessage())) { throw failure; }
            if (failure instanceof com.agentflow.llm.ModelClientException modelFailure
                    && !com.agentflow.llm.ModelClientException.PROVIDER_ERROR.equals(modelFailure.code())) {
                throw new IllegalStateException("RAG_CONFIGURATION_INVALID");
            }
            if (!allowFallback) { throw new IllegalStateException(stage); }
            degradation = stage;
        }
        check(control);
        var ranked = fusion.fuse(semantic, keyword.query(request.query()), request.topK());
        var hits = ranked.stream().map(candidate -> snapshot.chunks().get(candidate.chunkId())).toList();
        check(control);
        return new RetrievalPayload(snapshot.snapshotId(), degradation == null ? RetrievalPayload.Mode.HYBRID : RetrievalPayload.Mode.DEGRADED_KEYWORD,
                degradation == null ? RetrievalPayload.SemanticState.OK : RetrievalPayload.SemanticState.UNAVAILABLE,
                RetrievalPayload.KeywordState.OK, degradation, hits.isEmpty() ? RetrievalPayload.EmptyReason.NO_MATCH : RetrievalPayload.EmptyReason.NONE,
                false, Duration.ofNanos(System.nanoTime() - started).toMillis(), hits);
    }

    private static void check(ToolExecutionControl control) {
        try { control.checkActive(); }
        catch (ToolExecutionControl.ExecutionTimedOutException timeout) { throw new IllegalStateException("RAG_TIMEOUT"); }
    }
}
