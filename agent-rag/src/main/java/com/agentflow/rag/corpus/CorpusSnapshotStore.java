package com.agentflow.rag.corpus;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.Objects;
import java.util.Comparator;
import tools.jackson.databind.json.JsonMapper;
import com.agentflow.core.rag.RetrievedChunk;
import com.agentflow.rag.retrieval.KeywordIndex;

public final class CorpusSnapshotStore implements AutoCloseable {
    private static final long MAX_BYTES = 256L * 1_048_576;
    private final Path root;
    private final JsonMapper json = new JsonMapper();
    private FileChannel channel;
    private FileLock lock;
    private String storeId;
    private boolean closed;

    public record Pointer(String snapshotId, String manifestHash) {
        public Pointer { requireHash(snapshotId); requireHash(manifestHash); }
    }
    public record Preparation(String snapshotId, String collectionName, Pointer previousActive) {
        public Preparation { requireHash(snapshotId); }
    }
    public record StoreIdentity(int schemaVersion, String storeId) { }
    public record Retired(List<Pointer> snapshots) {
        public Retired { snapshots = List.copyOf(snapshots); }
    }
    public record StoredManifest(int schemaVersion, String state, String storeId, String collectionName,
                                 CorpusManifest corpus) { }

    public CorpusSnapshotStore(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            CorpusPathPolicy.checkAncestors(this.root);
            Path lockPath = this.root.resolve("corpus.lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) { CorpusPathPolicy.checkAncestors(lockPath); }
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException conflict) { throw failure("INDEX_LOCKED"); }
            if (lock == null) { throw failure("INDEX_LOCKED"); }
            if (Files.exists(this.root.resolve("store.json"), LinkOption.NOFOLLOW_LINKS)) {
                var identity = read("store.json", StoreIdentity.class);
                if (identity.schemaVersion() != 1 || identity.storeId() == null || !identity.storeId().matches("[a-f0-9]{32}")) {
                    throw failure("STORAGE_FAILURE");
                }
                storeId = identity.storeId();
            } else {
                try (var contents = Files.list(this.root)) {
                    if (contents.anyMatch(p -> !p.getFileName().toString().equals("corpus.lock"))) {
                        throw failure("STORAGE_FAILURE");
                    }
                }
                storeId = UUID.randomUUID().toString().replace("-", "");
                write("store.json", new StoreIdentity(1, storeId));
            }
            checkDisk(0);
            recover();
        } catch (IOException error) {
            close();
            throw failure("STORAGE_FAILURE");
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    public String storeId() { checkOpen(); return storeId; }
    public String collectionName(String snapshotId) { requireHash(snapshotId); return "hiagent_" + storeId() + "_" + snapshotId; }

    /** Returns false only when the same verified version is already active. */
    public boolean prepare(CorpusManifest manifest) {
        checkOpen();
        recover();
        Pointer active = active();
        if (active != null && active.snapshotId().equals(manifest.snapshotId())) {
            loadActive(manifest.profile());
            return false;
        }
        Preparation prepared = preparation();
        if (prepared != null && !prepared.snapshotId().equals(manifest.snapshotId())) { throw failure("STALE_PREPARATION"); }
        if (prepared == null) {
            if (retired().size() >= 2) { throw failure("RETIRED_LIMIT"); }
            if (Files.exists(snapshotPath(manifest.snapshotId()), LinkOption.NOFOLLOW_LINKS)) {
                throw failure("STALE_PREPARATION");
            }
            write("prepared.json", new Preparation(manifest.snapshotId(), collectionName(manifest.snapshotId()), active));
        }
        persist(manifest, "PREPARED");
        return true;
    }

    public void complete(CorpusManifest manifest) {
        checkOpen();
        Preparation prepared = preparation();
        if (prepared == null || !prepared.snapshotId().equals(manifest.snapshotId())) { throw failure("STORAGE_FAILURE"); }
        persist(manifest, "COMPLETE");
    }

    public void activate(String snapshotId) {
        checkOpen();
        var prepared = preparation();
        if (prepared == null || !prepared.snapshotId().equals(snapshotId)) { throw failure("STORAGE_FAILURE"); }
        String name = manifestName(snapshotId);
        StoredManifest manifest = read(name, StoredManifest.class);
        validateManifest(manifest, snapshotId);
        if (!"COMPLETE".equals(manifest.state())) { throw failure("STORAGE_FAILURE"); }
        validateSources(manifest.corpus());
        byte[] bytes = readBytes(name, 8 * 1_048_576);
        write("active.json", new Pointer(snapshotId, CorpusHash.bytes(bytes)));
        recover();
    }

    public CorpusSnapshot loadActive(CorpusManifest.IndexProfile profile) {
        checkOpen();
        Pointer pointer = active();
        if (pointer == null) { throw failure("STORAGE_FAILURE"); }
        byte[] bytes = readBytes(manifestName(pointer.snapshotId()), 8 * 1_048_576);
        if (!CorpusHash.bytes(bytes).equals(pointer.manifestHash())) { throw failure("STORAGE_FAILURE"); }
        StoredManifest stored;
        try { stored = json.readValue(bytes, StoredManifest.class); }
        catch (RuntimeException invalid) { throw failure("STORAGE_FAILURE"); }
        validateManifest(stored, pointer.snapshotId());
        if (!"COMPLETE".equals(stored.state())) { throw failure("STORAGE_FAILURE"); }
        if (!stored.corpus().profile().equals(profile)) { throw failure("INDEX_PROFILE_MISMATCH"); }
        var chunks = validateSources(stored.corpus());
        return new CorpusSnapshot(pointer.snapshotId(), stored.collectionName(), profile, chunks);
    }

    public Pointer active() { checkOpen(); return optional("active.json", Pointer.class); }
    public Preparation preparation() { checkOpen(); return optional("prepared.json", Preparation.class); }
    public List<Pointer> retired() {
        var value = optional("retired.json", Retired.class);
        if (value == null) { return List.of(); }
        if (value.snapshots().size() > 2 || value.snapshots().stream().map(Pointer::snapshotId).distinct().count() != value.snapshots().size()) {
            throw failure("STORAGE_FAILURE");
        }
        return value.snapshots();
    }

    /** Caller deletes the registered remote collection first; registration survives failure. */
    public void removeRegistered(String snapshotId) {
        checkOpen(); requireHash(snapshotId);
        Pointer active = active();
        if (active != null && active.snapshotId().equals(snapshotId)) { throw failure("STORAGE_FAILURE"); }
        var prepared = preparation();
        var retired = new ArrayList<>(retired());
        boolean isPrepared = prepared != null && prepared.snapshotId().equals(snapshotId);
        boolean isRetired = retired.removeIf(p -> p.snapshotId().equals(snapshotId));
        if (!isPrepared && !isRetired) { throw failure("STORAGE_FAILURE"); }
        Path target = snapshotPath(snapshotId);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                CorpusPathPolicy.checkAncestors(target);
                try (var paths = Files.walk(target)) {
                    var entries = paths.sorted(Comparator.reverseOrder()).toList();
                    for (Path entry : entries) {
                        if (!entry.toAbsolutePath().normalize().startsWith(target)) { throw failure("STORAGE_FAILURE"); }
                        CorpusPathPolicy.checkAncestors(entry);
                    }
                    for (Path entry : entries) { Files.delete(entry); }
                }
            }
            if (isPrepared) { Files.delete(root.resolve("prepared.json")); }
            if (isRetired) { write("retired.json", new Retired(retired)); }
        } catch (IOException error) { throw failure("STORAGE_FAILURE"); }
    }

    private void recover() {
        var prepared = preparation();
        if (prepared == null) { return; }
        if (!collectionName(prepared.snapshotId()).equals(prepared.collectionName())) { throw failure("STORAGE_FAILURE"); }
        Pointer active = active();
        if (active != null && active.snapshotId().equals(prepared.snapshotId())) {
            var retired = new ArrayList<>(retired());
            if (prepared.previousActive() != null && !retired.contains(prepared.previousActive())) {
                if (retired.size() >= 2) { throw failure("RETIRED_LIMIT"); }
                retired.add(prepared.previousActive());
            }
            write("retired.json", new Retired(retired));
            try { Files.delete(root.resolve("prepared.json")); }
            catch (IOException error) { throw failure("STORAGE_FAILURE"); }
        } else if (!Objects.equals(active, prepared.previousActive())) {
            throw failure("STORAGE_FAILURE");
        }
    }

    private void persist(CorpusManifest manifest, String state) {
        String base = "snapshots/" + manifest.snapshotId() + "/";
        for (var document : manifest.documents()) {
            writeBytes(base + "documents/" + document.docId() + ".txt", document.text().getBytes(StandardCharsets.UTF_8));
            for (var chunk : document.chunks()) {
                writeBytes(base + "chunks/" + chunk.chunkId() + ".txt", chunk.text().getBytes(StandardCharsets.UTF_8));
            }
        }
        write(manifestName(manifest.snapshotId()), new StoredManifest(1, state, storeId, collectionName(manifest.snapshotId()), manifest));
    }

    private HashMap<String, RetrievedChunk> validateSources(CorpusManifest manifest) {
        var result = new HashMap<String, RetrievedChunk>();
        var texts = new HashMap<String, String>();
        var tuple = new ArrayList<String>(List.of("snapshot-v1", manifest.profile().id()));
        int skipped = 0;
        try {
            for (var document : manifest.documents()) {
                String name = "snapshots/" + manifest.snapshotId() + "/documents/" + document.docId() + ".txt";
                byte[] source = readBytes(name, 3 * 1_048_576);
                var rebuilt = new DocumentChunker().read(document.relativePath(), source,
                        manifest.profile().chunkSize(), manifest.profile().overlap());
                if (!rebuilt.equals(document)) { throw failure("STORAGE_FAILURE"); }
                tuple.add(document.docId()); tuple.add(document.version());
                if (document.chunks().isEmpty()) { skipped++; }
                for (var chunk : document.chunks()) {
                    byte[] bytes = readBytes("snapshots/" + manifest.snapshotId() + "/chunks/" + chunk.chunkId() + ".txt", 3200);
                    if (!CorpusHash.bytes(bytes).equals(chunk.contentHash())) { throw failure("STORAGE_FAILURE"); }
                    var value = new RetrievedChunk(manifest.snapshotId(), document.docId(), document.version(), chunk.chunkId(),
                            chunk.contentHash(), document.relativePath(), document.title(), chunk.start(), chunk.end(), chunk.text());
                    if (result.put(chunk.chunkId(), value) != null) { throw failure("STORAGE_FAILURE"); }
                    texts.put(chunk.chunkId(), chunk.text());
                }
            }
            if (!CorpusHash.tuple(tuple.toArray(String[]::new)).equals(manifest.snapshotId())
                    || result.isEmpty() || result.size() > 10_000 || result.size() != manifest.chunkCount()
                    || skipped != manifest.skippedEmptyCount() || manifest.documents().size() > 100) {
                throw failure("STORAGE_FAILURE");
            }
            new KeywordIndex(texts);
            return result;
        } catch (RuntimeException invalid) { throw failure("STORAGE_FAILURE"); }
    }

    private void validateManifest(StoredManifest stored, String snapshotId) {
        if (stored.schemaVersion() != 1 || !storeId.equals(stored.storeId()) || stored.corpus() == null
                || !snapshotId.equals(stored.corpus().snapshotId()) || !collectionName(snapshotId).equals(stored.collectionName())) {
            throw failure("STORAGE_FAILURE");
        }
    }

    private void checkDisk(long additional) {
        try (var paths = Files.walk(root)) {
            long size = 0;
            for (Path path : paths.toList()) {
                CorpusPathPolicy.checkAncestors(path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { size += Files.size(path); }
                if (size + additional > MAX_BYTES) { throw failure("ARCHIVE_LIMIT"); }
            }
        } catch (IOException error) { throw failure("STORAGE_FAILURE"); }
    }

    private <T> T optional(String name, Class<T> type) {
        checkOpen();
        return Files.exists(resolve(name), LinkOption.NOFOLLOW_LINKS) ? read(name, type) : null;
    }
    private <T> T read(String name, Class<T> type) {
        try { return Objects.requireNonNull(json.readValue(readBytes(name, 8 * 1_048_576), type)); }
        catch (RuntimeException invalid) { throw failure("STORAGE_FAILURE"); }
    }
    private byte[] readBytes(String name, int limit) {
        Path path = resolve(name);
        try {
            CorpusPathPolicy.checkAncestors(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) { throw failure("STORAGE_FAILURE"); }
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit) { throw failure("STORAGE_FAILURE"); }
                return bytes;
            }
        } catch (IOException error) { throw failure("STORAGE_FAILURE"); }
    }
    private void write(String name, Object value) {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > 8 * 1_048_576) { throw failure("ARCHIVE_LIMIT"); }
        writeBytes(name, bytes);
    }
    private void writeBytes(String name, byte[] bytes) {
        Path destination = resolve(name);
        checkDisk(bytes.length);
        try {
            Files.createDirectories(destination.getParent());
            CorpusPathPolicy.checkAncestors(destination.getParent());
            Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
            try (var output = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { output.write(buffer); }
                output.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) { throw failure("ATOMIC_ACTIVATION_UNSUPPORTED"); }
        catch (IOException error) { throw failure("STORAGE_FAILURE"); }
    }
    private Path resolve(String name) {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root)) { throw failure("STORAGE_FAILURE"); }
        return path;
    }
    private Path snapshotPath(String id) { requireHash(id); return resolve("snapshots/" + id); }
    private String manifestName(String id) { requireHash(id); return "snapshots/" + id + "/manifest.json"; }
    private static void requireHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) { throw failure("STORAGE_FAILURE"); }
    }
    private void checkOpen() { if (closed) { throw failure("STORAGE_FAILURE"); } }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }

    @Override public void close() {
        closed = true;
        try { if (lock != null) { lock.release(); } }
        catch (IOException ignored) { /* Channel closure also releases the lock. */ }
        finally {
            try { if (channel != null) { channel.close(); } }
            catch (IOException error) { throw failure("STORAGE_FAILURE"); }
        }
    }
}
