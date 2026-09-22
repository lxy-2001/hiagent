package com.agentflow.rag.qdrant;

import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.corpus.CorpusManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in against the documented Qdrant 1.13.4 target; no paid embedding. */
@EnabledIfSystemProperty(named = "feature005.realQdrant", matches = "true")
class RealQdrantSmokeTest {
    @Test
    void roundTripsDeterministicVectorsUsingAllProtocolOperations() {
        String store = UUID.randomUUID().toString().replace("-", "");
        String snapshot = "b".repeat(64), chunk = "c".repeat(64), hash = "d".repeat(64);
        String collection = "hiagent_" + store + "_" + snapshot;
        var index = new QdrantVectorIndex(URI.create(System.getProperty("feature005.qdrantUrl", "http://127.0.0.1:6333")),
                System.getenv("QDRANT_API_KEY"), store);
        assertThat(index.collectionExists(collection, control())).isFalse();
        boolean created = false;
        try {
            index.ensureCollection(collection, 3, control());
            created = true;
            index.ensureCollection(collection, 3, control());
            var point = new VectorIndex.VectorPoint(CorpusManifest.pointId(chunk), List.of(1d, .2, .1), snapshot, chunk, hash);
            index.upsert(collection, List.of(point), control());
            assertThat(index.countExact(collection, control())).isEqualTo(1);
            assertThat(index.readPoints(collection, List.of(point.pointId()), control()))
                    .containsExactly(new VectorIndex.StoredPoint(point.pointId(), snapshot, chunk, hash));
            assertThat(index.search(collection, snapshot, point.vector(), 40, .35, control())).hasSize(1);
        } finally {
            if (created) { index.deleteOwnedCollection(collection, control()); }
        }
        assertThat(index.collectionExists(collection, control())).isFalse();
    }
    private ToolExecutionControl control() {
        return new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofSeconds(5));
    }
}
