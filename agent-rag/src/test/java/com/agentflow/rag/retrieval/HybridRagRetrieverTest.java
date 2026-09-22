package com.agentflow.rag.retrieval;

import com.agentflow.core.rag.*;
import com.agentflow.core.model.*;
import com.agentflow.core.runtime.*;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.rag.corpus.*;
import com.agentflow.rag.qdrant.VectorIndex;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridRagRetrieverTest {
    private final String snapshotId = "a".repeat(64);
    private final String chunkId = "b".repeat(64);
    private final RetrievedChunk chunk = new RetrievedChunk(snapshotId, "c".repeat(64), "d".repeat(64), chunkId,
            CorpusHash.content("Java runtime"), "java.md", "Java", 0, 12, "Java runtime");
    private final CorpusSnapshot snapshot = new CorpusSnapshot(snapshotId, "collection",
            new CorpusManifest.IndexProfile("test", "fixed", 3, 800, 100), Map.of(chunkId, chunk));

    @Test
    void combinesRealKeywordAndSemanticCandidatesWithBoundedOptions() {
        var embedding = mock(DeadlineAwareEmbeddingClient.class);
        when(embedding.embed(anyString(), any())).thenAnswer(call -> {
            EmbeddingCallOptions options = call.getArgument(1);
            assertThat(options.remainingTime()).isLessThanOrEqualTo(Duration.ofSeconds(4));
            return List.of(1d, 2d, 3d);
        });
        var vectors = mock(VectorIndex.class);
        when(vectors.search(anyString(), anyString(), anyList(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(new VectorIndex.VectorHit(CorpusManifest.pointId(chunkId), snapshotId, chunkId, chunk.contentHash(), .9)));
        var payload = new HybridRagRetriever(snapshot, embedding, vectors, false).retrieve(new RetrievalRequest("Java"), control());
        assertThat(payload.hits()).containsExactly(chunk);
        assertThat(payload.mode()).isEqualTo(RetrievalPayload.Mode.HYBRID);
        assertThat(payload.emptyReason()).isEqualTo(RetrievalPayload.EmptyReason.NONE);
    }

    @Test
    void fallbackIsExplicitAndCorruptionNeverFallsBack() {
        var embedding = mock(DeadlineAwareEmbeddingClient.class);
        when(embedding.embed(anyString(), any())).thenThrow(new IllegalStateException("provider down"));
        var vectors = mock(VectorIndex.class);
        assertThatThrownBy(() -> new HybridRagRetriever(snapshot, embedding, vectors, false)
                .retrieve(new RetrievalRequest("Java"), control())).hasMessage("RAG_EMBEDDING_UNAVAILABLE");
        var degraded = new HybridRagRetriever(snapshot, embedding, vectors, true).retrieve(new RetrievalRequest("Java"), control());
        assertThat(degraded.mode()).isEqualTo(RetrievalPayload.Mode.DEGRADED_KEYWORD);
        assertThat(degraded.hits()).containsExactly(chunk);
        doReturn(List.of(1d, 2d, 3d)).when(embedding).embed(anyString(), any());
        when(vectors.search(anyString(), anyString(), anyList(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of(new VectorIndex.VectorHit(CorpusManifest.pointId(chunkId), snapshotId, chunkId, "f".repeat(64), .9)));
        assertThatThrownBy(() -> new HybridRagRetriever(snapshot, embedding, vectors, true)
                .retrieve(new RetrievalRequest("Java"), control())).hasMessage("RAG_SOURCE_INVALID");
    }

    @Test
    void cancellationWinsOverLateProviderFailureAndEmptyIsNotFailure() {
        var cancelled = new AtomicBoolean();
        var embedding = mock(DeadlineAwareEmbeddingClient.class);
        when(embedding.embed(anyString(), any())).thenAnswer(call -> { cancelled.set(true); throw new IllegalStateException(); });
        var vectors = mock(VectorIndex.class);
        var control = new ToolExecutionControl(cancelled::get, TimeSource.system(), Duration.ofSeconds(3));
        assertThatThrownBy(() -> new HybridRagRetriever(snapshot, embedding, vectors, true)
                .retrieve(new RetrievalRequest("Java"), control)).isInstanceOf(CancellationException.class);
        doReturn(List.of(1d, 2d, 3d)).when(embedding).embed(anyString(), any());
        when(vectors.search(anyString(), anyString(), anyList(), anyInt(), anyDouble(), any())).thenReturn(List.of());
        var empty = new HybridRagRetriever(snapshot, embedding, vectors, false).retrieve(new RetrievalRequest("absent"), control());
        assertThat(empty.emptyReason()).isEqualTo(RetrievalPayload.EmptyReason.NO_MATCH);
        assertThat(empty.mode()).isEqualTo(RetrievalPayload.Mode.HYBRID);
    }

    private ToolExecutionControl control() {
        return new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofSeconds(5));
    }

    @Test
    void semanticSubDeadlineMayDegradeButTotalDeadlineNeverDoes() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var embedding = mock(DeadlineAwareEmbeddingClient.class);
        when(embedding.embed(anyString(), any())).thenAnswer(call -> {
            clock.set(Duration.ofMillis(4100).toNanos());
            return List.of(1d, 2d, 3d);
        });
        var vectors = mock(VectorIndex.class);
        var parent = new ToolExecutionControl(CancellationSignal.NONE, clock::get, Duration.ofSeconds(5));
        var result = new HybridRagRetriever(snapshot, embedding, vectors, true).retrieve(new RetrievalRequest("Java"), parent);
        assertThat(result.mode()).isEqualTo(RetrievalPayload.Mode.DEGRADED_KEYWORD);
        verifyNoInteractions(vectors);
        clock.set(0);
        doAnswer(call -> { clock.set(Duration.ofSeconds(5).toNanos()); return List.of(1d, 2d, 3d); })
                .when(embedding).embed(anyString(), any());
        var total = new ToolExecutionControl(CancellationSignal.NONE, clock::get, Duration.ofSeconds(5));
        assertThatThrownBy(() -> new HybridRagRetriever(snapshot, embedding, vectors, true)
                .retrieve(new RetrievalRequest("Java"), total)).hasMessage("RAG_TIMEOUT");
    }
}
