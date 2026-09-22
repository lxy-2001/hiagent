package com.agentflow.rag.qdrant;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.support.QdrantProtocolFixture;
import com.agentflow.rag.corpus.CorpusManifest;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class QdrantVectorIndexTest {
    private static final String STORE = "a".repeat(32);
    private static final String SNAPSHOT = "b".repeat(64);
    private static final String CHUNK = "c".repeat(64);
    private static final String HASH = "d".repeat(64);
    private static final String COLLECTION = "hiagent_" + STORE + "_" + SNAPSHOT;

    @Test
    void executesSevenProtocolOperationsWithBoundedPayloads() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            var index = new QdrantVectorIndex(fixture.uri(), "", STORE);
            fixture.enqueue(404, "{\"status\":{\"error\":\"missing\"}}");
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":true}");
            index.ensureCollection(COLLECTION, 3, control());
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}");
            var point = new VectorIndex.VectorPoint(CorpusManifest.pointId(CHUNK), List.of(1d, 2d, 3d), SNAPSHOT, CHUNK, HASH);
            index.upsert(COLLECTION, List.of(point), control());
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":{\"count\":1}}");
            assertThat(index.countExact(COLLECTION, control())).isEqualTo(1);
            String payload = "\"id\":\"" + point.pointId() + "\",\"payload\":{\"snapshotId\":\"" + SNAPSHOT
                    + "\",\"chunkId\":\"" + CHUNK + "\",\"contentHash\":\"" + HASH + "\"}";
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":[{" + payload + "}]}");
            assertThat(index.readPoints(COLLECTION, List.of(point.pointId()), control())).hasSize(1);
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":[{" + payload + ",\"score\":0.9}]}");
            assertThat(index.search(COLLECTION, SNAPSHOT, point.vector(), 40, .35, control())).hasSize(1);
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":true}");
            index.deleteOwnedCollection(COLLECTION, control());
            assertThat(fixture.requests()).extracting(QdrantProtocolFixture.Request::method)
                    .containsExactly("GET", "PUT", "PUT", "POST", "POST", "POST", "DELETE");
            assertThat(fixture.requests().get(5).body()).contains("score_threshold", "snapshotId", "with_vector");
        }
    }

    @Test
    void rejectsConfigurationMismatchForeignCollectionsMissingPointsAndServiceFailures() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            var index = new QdrantVectorIndex(fixture.uri(), "", STORE);
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2,\"distance\":\"Cosine\"}}}}}");
            assertThatThrownBy(() -> index.ensureCollection(COLLECTION, 3, control())).hasMessage("RAG_CONFIGURATION_INVALID");
            assertThatThrownBy(() -> index.countExact("hiagent_" + "f".repeat(32) + "_" + SNAPSHOT, control()))
                    .hasMessage("RAG_CONFIGURATION_INVALID");
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":[]}");
            assertThatThrownBy(() -> index.readPoints(COLLECTION, List.of(CorpusManifest.pointId(CHUNK)), control()))
                    .hasMessage("RAG_SOURCE_INVALID");
            for (int status : new int[]{301, 401, 500}) {
                fixture.enqueue(status, "{\"private\":\"body\"}");
                assertThatThrownBy(() -> index.countExact(COLLECTION, control())).hasMessage("RAG_VECTOR_UNAVAILABLE");
            }
        }
    }

    @Test
    void rejectsOversizedResponseAndHonorsWholeBodyDeadline() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            var index = new QdrantVectorIndex(fixture.uri(), "", STORE);
            fixture.enqueue(200, "x".repeat(131_073));
            assertThatThrownBy(() -> index.search(COLLECTION, SNAPSHOT, List.of(1d), 40, .35, control()))
                    .hasMessage("RAG_VECTOR_UNAVAILABLE");
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":{\"count\":1}}", Duration.ofSeconds(2));
            var deadline = new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofMillis(150));
            long start = System.nanoTime();
            assertThatThrownBy(() -> index.countExact(COLLECTION, deadline)).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        }
    }

    private ToolExecutionControl control() {
        return new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofSeconds(3));
    }

    @Test
    void cancellationPreventsRequestAndMalformedIdentityNeverBecomesEmptySuccess() throws Exception {
        try (var fixture = new QdrantProtocolFixture()) {
            var index = new QdrantVectorIndex(fixture.uri(), "", STORE);
            assertThatThrownBy(() -> index.countExact(COLLECTION,
                    new ToolExecutionControl(() -> true, TimeSource.system(), Duration.ofSeconds(1))))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(fixture.requests()).isEmpty();
            fixture.enqueue(200, "{broken");
            assertThatThrownBy(() -> index.countExact(COLLECTION, control())).hasMessage("RAG_SOURCE_INVALID");
            fixture.enqueue(200, "{\"status\":\"ok\",\"result\":[{\"id\":\"bad\",\"payload\":{}}]}");
            assertThatThrownBy(() -> index.search(COLLECTION, SNAPSHOT, List.of(1d), 40, .35, control()))
                    .hasMessage("RAG_SOURCE_INVALID");
        }
    }
}
