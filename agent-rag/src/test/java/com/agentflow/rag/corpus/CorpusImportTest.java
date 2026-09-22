package com.agentflow.rag.corpus;

import com.agentflow.core.model.DeadlineAwareEmbeddingClient;
import com.agentflow.core.model.EmbeddingCallOptions;
import com.agentflow.core.cancel.CancellationSignal;
import com.agentflow.core.runtime.TimeSource;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.qdrant.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CorpusImportTest {
    @TempDir Path temporary;
    private final CorpusManifest.IndexProfile profile = new CorpusManifest.IndexProfile("test", "fixed", 3, 800, 100);
    private final AtomicInteger calls = new AtomicInteger();
    private final DeadlineAwareEmbeddingClient embedding = new DeadlineAwareEmbeddingClient() {
        @Override public List<Double> embed(String text) { throw new AssertionError("bounded port required"); }
        @Override public List<Double> embed(String text, EmbeddingCallOptions options) {
            calls.incrementAndGet();
            return List.of(1d, 2d, 3d);
        }
    };

    @Test
    void importsIdempotentlyAndRetainsPreviousSnapshotOnVectorFailure() throws Exception {
        var first = manifest("first");
        var second = manifest("second");
        var vectors = new MemoryIndex();
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            var importer = new CorpusImporter(store, embedding, vectors);
            assertThat(importer.importCorpus(first, control()).outcome()).isEqualTo("SUCCESS");
            assertThat(calls).hasValue(1);
            assertThat(importer.importCorpus(first, control()).reused()).isTrue();
            assertThat(calls).hasValue(1);
            vectors.failWrite = true;
            assertThat(importer.importCorpus(second, control()).outcome()).isEqualTo("FAILED");
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(first.snapshotId());
            vectors.failWrite = false;
            assertThat(importer.importCorpus(second, control()).outcome()).isEqualTo("SUCCESS");
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(second.snapshotId());
            importer.cleanupRetired(control());
            assertThat(store.retired()).isEmpty();
            assertThat(vectors.collections).hasSize(1);
        }
    }

    @Test
    void verifiesEveryIdBeforeActivationAndKeepsCleanupRegistrationOnFailure() throws Exception {
        var corpus = manifest("text");
        var vectors = new MemoryIndex();
        vectors.wrongRead = true;
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            var importer = new CorpusImporter(store, embedding, vectors);
            assertThat(importer.importCorpus(corpus, control()).errorCode()).isEqualTo("VECTOR_INDEX_FAILED");
            assertThat(store.active()).isNull();
            assertThat(store.preparation()).isNotNull();
            vectors.failDelete = true;
            assertThatThrownBy(() -> importer.cleanupPrepared(control())).isInstanceOf(RuntimeException.class);
            assertThat(store.preparation()).isNotNull();
            vectors.failDelete = false;
            importer.cleanupPrepared(control());
            assertThat(store.preparation()).isNull();
        }
    }

    @Test
    void refusesUnknownExistingCollectionAndExpiredImport() throws Exception {
        var corpus = manifest("text");
        var vectors = new MemoryIndex();
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            vectors.collections.put(store.collectionName(corpus.snapshotId()), new HashMap<>());
            var importer = new CorpusImporter(store, embedding, vectors);
            assertThat(importer.importCorpus(corpus, control()).errorCode()).isEqualTo("VECTOR_INDEX_FAILED");
            assertThat(store.active()).isNull();
            var expired = new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ZERO);
            assertThat(importer.importCorpus(corpus, expired).errorCode()).isEqualTo("IMPORT_TIMEOUT");
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void rejectsWrongCountAndInvalidEmbeddingBeforeActivation() throws Exception {
        var corpus = manifest("text");
        var vectors = new MemoryIndex();
        vectors.wrongCount = true;
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            assertThat(new CorpusImporter(store, embedding, vectors).importCorpus(corpus, control()).errorCode())
                    .isEqualTo("VECTOR_INDEX_FAILED");
            assertThat(store.active()).isNull();
        }
        DeadlineAwareEmbeddingClient invalid = new DeadlineAwareEmbeddingClient() {
            @Override public List<Double> embed(String text) { throw new AssertionError(); }
            @Override public List<Double> embed(String text, EmbeddingCallOptions options) { return List.of(0d, 0d, 0d); }
        };
        try (var store = new CorpusSnapshotStore(temporary.resolve("other"))) {
            assertThat(new CorpusImporter(store, invalid, new MemoryIndex()).importCorpus(corpus, control()).errorCode())
                    .isEqualTo("EMBEDDING_FAILED");
            assertThat(store.active()).isNull();
        }
    }

    private CorpusManifest manifest(String text) throws Exception {
        var source = temporary.resolve("source"); Files.createDirectories(source);
        Files.writeString(source.resolve("source.md"), text);
        return CorpusManifest.read(source, profile);
    }

    @Test
    void reportsCommittedMetadataFailureAsSuccessAndUnreadablePointerAsIndeterminate() throws Exception {
        var corpus = manifest("text");
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            var vectors = new MemoryIndex();
            vectors.afterRead = () -> {
                try { Files.createDirectory(root.resolve("retired.json.tmp")); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            };
            var result = new CorpusImporter(store, embedding, vectors).importCorpus(corpus, control());
            assertThat(result.outcome()).isEqualTo("SUCCESS");
            assertThat(result.warningCode()).isEqualTo("COMMIT_METADATA_PENDING");
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(corpus.snapshotId());
        }
        Path other = temporary.resolve("other");
        try (var store = new CorpusSnapshotStore(other)) {
            var vectors = new MemoryIndex();
            vectors.afterRead = () -> {
                try { Files.createDirectory(other.resolve("active.json")); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            };
            var result = new CorpusImporter(store, embedding, vectors).importCorpus(corpus, control());
            assertThat(result.outcome()).isEqualTo("INDETERMINATE");
            assertThat(result.errorCode()).isEqualTo("STORAGE_FAILURE");
            assertThat(store.preparation()).isNotNull();
        }
    }
    private ToolExecutionControl control() {
        return new ToolExecutionControl(CancellationSignal.NONE, TimeSource.system(), Duration.ofSeconds(5));
    }

    static final class MemoryIndex implements VectorIndex {
        final Map<String, Map<UUID, VectorPoint>> collections = new HashMap<>();
        boolean failWrite;
        boolean wrongRead;
        boolean wrongCount;
        boolean failDelete;
        Runnable afterRead = () -> { };
        @Override public boolean collectionExists(String name, ToolExecutionControl control) { return collections.containsKey(name); }
        @Override public void ensureCollection(String name, int dimensions, ToolExecutionControl control) { collections.computeIfAbsent(name, ignored -> new HashMap<>()); }
        @Override public void upsert(String name, List<VectorPoint> points, ToolExecutionControl control) {
            if (failWrite) { throw new IllegalStateException("RAG_VECTOR_UNAVAILABLE"); }
            points.forEach(point -> collections.get(name).put(point.pointId(), point));
        }
        @Override public long countExact(String name, ToolExecutionControl control) { return wrongCount ? 999 : collections.get(name).size(); }
        @Override public List<StoredPoint> readPoints(String name, List<UUID> ids, ToolExecutionControl control) {
            if (wrongRead) { return List.of(); }
            afterRead.run();
            return ids.stream().map(id -> collections.get(name).get(id))
                    .map(p -> new StoredPoint(p.pointId(), p.snapshotId(), p.chunkId(), p.contentHash())).toList();
        }
        @Override public List<VectorHit> search(String name, String snapshot, List<Double> vector, int limit, double threshold, ToolExecutionControl control) { throw new AssertionError("not queried during import"); }
        @Override public void deleteOwnedCollection(String name, ToolExecutionControl control) {
            if (failDelete) { throw new IllegalStateException("RAG_VECTOR_UNAVAILABLE"); }
            collections.remove(name);
        }
    }
}
