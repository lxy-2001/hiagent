package com.agentflow.rag.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class CorpusSnapshotStoreTest {
    @TempDir Path temporary;
    private final CorpusManifest.IndexProfile profile = new CorpusManifest.IndexProfile("test", "fixed", 3, 800, 100);

    @Test
    void holdsExclusiveLockAndPersistsStoreIdentity() {
        String id;
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            id = store.storeId();
            assertThat(id).matches("[a-f0-9]{32}");
            assertThatThrownBy(() -> new CorpusSnapshotStore(root)).hasMessage("INDEX_LOCKED");
        }
        try (var store = new CorpusSnapshotStore(root); var other = new CorpusSnapshotStore(temporary.resolve("other"))) {
            assertThat(store.storeId()).isEqualTo(id);
            assertThat(store.collectionName("a".repeat(64))).isNotEqualTo(other.collectionName("a".repeat(64)));
        }
    }

    @Test
    void onlyCompleteSnapshotsBecomeActiveAndSourcesSurviveRestart() throws Exception {
        var manifest = manifest("original");
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            assertThat(store.prepare(manifest)).isTrue();
            assertThatThrownBy(() -> store.activate(manifest.snapshotId())).hasMessage("STORAGE_FAILURE");
            store.complete(manifest);
            store.activate(manifest.snapshotId());
            assertThat(store.loadActive(profile).chunks()).hasSize(1);
            assertThat(store.prepare(manifest)).isFalse();
        }
        try (var store = new CorpusSnapshotStore(root)) {
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(manifest.snapshotId());
            assertThat(store.loadActive(profile).chunks().values()).extracting(c -> c.text()).containsExactly("original");
            assertThatThrownBy(() -> store.loadActive(new CorpusManifest.IndexProfile("other", "fixed", 3, 800, 100)))
                    .hasMessage("INDEX_PROFILE_MISMATCH");
        }
    }

    @Test
    void incompleteCandidatePreservesPreviousActiveAndRejectsAnotherCandidate() throws Exception {
        var first = manifest("old");
        var second = manifest("new");
        var third = manifest("third");
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            store.prepare(first); store.complete(first); store.activate(first.snapshotId());
            store.prepare(second);
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(first.snapshotId());
            assertThatThrownBy(() -> store.prepare(third)).hasMessage("STALE_PREPARATION");
        }
    }

    @Test
    void rejectsCorruptedContentOrLostStoreIdentity() throws Exception {
        var manifest = manifest("safe");
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            store.prepare(manifest); store.complete(manifest); store.activate(manifest.snapshotId());
        }
        String chunk = manifest.documents().get(0).chunks().get(0).chunkId();
        Files.writeString(root.resolve("snapshots/" + manifest.snapshotId() + "/chunks/" + chunk + ".txt"), "tampered");
        try (var store = new CorpusSnapshotStore(root)) {
            assertThatThrownBy(() -> store.loadActive(profile)).hasMessage("STORAGE_FAILURE");
        }
        Files.delete(root.resolve("store.json"));
        assertThatThrownBy(() -> new CorpusSnapshotStore(root)).hasMessage("STORAGE_FAILURE");
    }

    private CorpusManifest manifest(String text) throws Exception {
        Path source = temporary.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("java.md"), text);
        return CorpusManifest.read(source, profile);
    }

    @Test
    void recoversCommittedPreparationUsingExplicitPreviousActiveAndCleansOnlyRegisteredVersions() throws Exception {
        var first = manifest("first");
        var second = manifest("second");
        Path root = temporary.resolve("store");
        byte[] preparation;
        try (var store = new CorpusSnapshotStore(root)) {
            store.prepare(first); store.complete(first); store.activate(first.snapshotId());
            store.prepare(second); store.complete(second);
            preparation = Files.readAllBytes(root.resolve("prepared.json"));
            store.activate(second.snapshotId());
            assertThat(store.retired()).hasSize(1);
            assertThatThrownBy(() -> store.removeRegistered(second.snapshotId())).hasMessage("STORAGE_FAILURE");
            assertThatThrownBy(() -> store.removeRegistered("f".repeat(64))).hasMessage("STORAGE_FAILURE");
        }
        // Simulate crash after active commit but before retirement/preparation cleanup.
        Files.write(root.resolve("prepared.json"), preparation);
        Files.delete(root.resolve("retired.json"));
        try (var store = new CorpusSnapshotStore(root)) {
            assertThat(store.preparation()).isNull();
            assertThat(store.retired()).extracting(CorpusSnapshotStore.Pointer::snapshotId).containsExactly(first.snapshotId());
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(second.snapshotId());
            store.removeRegistered(first.snapshotId());
            assertThat(store.retired()).isEmpty();
            assertThat(root.resolve("snapshots/" + first.snapshotId())).doesNotExist();
        }
    }

    @Test
    void refusesFullRetirementSlotsAndDiskLimit() throws Exception {
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            for (String text : new String[]{"a", "b", "c"}) {
                var manifest = manifest(text);
                store.prepare(manifest); store.complete(manifest); store.activate(manifest.snapshotId());
            }
            var fourth = manifest("d");
            assertThatThrownBy(() -> store.prepare(fourth)).hasMessage("RETIRED_LIMIT");
        }
        try (var file = new java.io.RandomAccessFile(root.resolve("oversized.bin").toFile(), "rw")) {
            file.setLength(256L * 1_048_576 + 1);
        }
        assertThatThrownBy(() -> new CorpusSnapshotStore(root)).hasMessage("ARCHIVE_LIMIT");
    }

    @Test
    void metadataFailureAfterCommitCanBeDistinguishedByReadback() throws Exception {
        var first = manifest("a");
        var second = manifest("b");
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            store.prepare(first); store.complete(first); store.activate(first.snapshotId());
            store.prepare(second); store.complete(second);
            Files.createDirectory(root.resolve("retired.json.tmp"));
            assertThatThrownBy(() -> store.activate(second.snapshotId())).hasMessage("STORAGE_FAILURE");
            assertThat(store.active().snapshotId()).isEqualTo(second.snapshotId());
            assertThat(store.preparation()).isNotNull();
            Files.delete(root.resolve("retired.json.tmp"));
        }
        try (var store = new CorpusSnapshotStore(root)) {
            assertThat(store.preparation()).isNull();
            assertThat(store.retired()).hasSize(1);
        }
    }

    @Test
    void failedActiveWriteLeavesOldPointerIntact() throws Exception {
        var first = manifest("a");
        var second = manifest("b");
        Path root = temporary.resolve("store");
        try (var store = new CorpusSnapshotStore(root)) {
            store.prepare(first); store.complete(first); store.activate(first.snapshotId());
            store.prepare(second); store.complete(second);
            Files.createDirectory(root.resolve("active.json.tmp"));
            assertThatThrownBy(() -> store.activate(second.snapshotId())).hasMessage("STORAGE_FAILURE");
            assertThat(store.loadActive(profile).snapshotId()).isEqualTo(first.snapshotId());
        }
    }

    @Test
    void anotherJvmCannotAcquireTheSameStore() throws Exception {
        Path root = temporary.resolve("process-store");
        try (var store = new CorpusSnapshotStore(root)) {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                    LockProbe.class.getName(), root.toString()).redirectErrorStream(true).start();
            try {
                assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(new String(process.getInputStream().readAllBytes())).isZero();
            } finally { if (process.isAlive()) { process.destroyForcibly(); } }
        }
    }

    public static final class LockProbe {
        public static void main(String[] args) {
            try (var ignored = new CorpusSnapshotStore(Path.of(args[0]))) { System.exit(2); }
            catch (IllegalStateException error) { System.exit("INDEX_LOCKED".equals(error.getMessage()) ? 0 : 3); }
        }
    }

    @Test
    void preservesWhitespaceOnlyWindowsInsideANonemptyDocument() throws Exception {
        var manifest = manifest(" ".repeat(800) + "Java");
        try (var store = new CorpusSnapshotStore(temporary.resolve("store"))) {
            store.prepare(manifest); store.complete(manifest); store.activate(manifest.snapshotId());
            assertThat(store.loadActive(profile).chunks()).hasSize(2);
        }
    }
}
