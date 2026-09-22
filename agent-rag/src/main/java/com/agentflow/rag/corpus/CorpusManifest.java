package com.agentflow.rag.corpus;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import com.agentflow.rag.retrieval.KeywordIndex;

/** Validated complete input, before any index or storage side effects. */
public record CorpusManifest(String snapshotId, IndexProfile profile, List<DocumentChunker.Document> documents,
                             long inputBytes, int skippedEmptyCount, int chunkCount) {
    public CorpusManifest { documents = List.copyOf(documents); }

    public record IndexProfile(String embeddingSpaceId, String embeddingModel, int dimensions, int chunkSize, int overlap) {
        public IndexProfile {
            if (embeddingSpaceId == null || !embeddingSpaceId.matches("[A-Za-z0-9._-]{1,64}")
                    || embeddingModel == null || embeddingModel.isBlank() || embeddingModel.length() > 128
                    || dimensions < 1 || dimensions > 4096 || chunkSize < 200 || chunkSize > 800
                    || overlap < 0 || overlap > Math.min(200, chunkSize - 1)) {
                throw new IllegalArgumentException("INDEX_PROFILE_MISMATCH");
            }
        }

        public String id() {
            return CorpusHash.tuple("profile-v1", "1", embeddingSpaceId, embeddingModel, Integer.toString(dimensions),
                    "COSINE", DocumentChunker.NORMALIZATION_VERSION, DocumentChunker.CHUNKER_VERSION,
                    Integer.toString(chunkSize), Integer.toString(overlap), "ascii-han-overlap-v1", "rrf60-v1");
        }
    }

    public static CorpusManifest read(Path root, IndexProfile profile) {
        var sources = new CorpusPathPolicy().read(root);
        var documents = new ArrayList<DocumentChunker.Document>();
        var texts = new HashMap<String, String>();
        var pointIds = new HashSet<UUID>();
        var identities = new ArrayList<String>(List.of("snapshot-v1", profile.id()));
        long bytes = 0;
        int skipped = 0;
        var chunker = new DocumentChunker();
        for (var source : sources) {
            bytes += source.bytes().length;
            var document = chunker.read(source.relativePath(), source.bytes(), profile.chunkSize(), profile.overlap());
            documents.add(document);
            identities.add(document.docId());
            identities.add(document.version());
            if (document.chunks().isEmpty()) { skipped++; }
            for (var chunk : document.chunks()) {
                if (texts.put(chunk.chunkId(), chunk.text()) != null || !pointIds.add(pointId(chunk.chunkId()))) {
                    throw new IllegalArgumentException("CORPUS_LIMIT");
                }
                if (texts.size() > 10_000) { throw new IllegalArgumentException("CORPUS_LIMIT"); }
            }
        }
        if (texts.isEmpty()) { throw new IllegalArgumentException("EMPTY_CORPUS"); }
        new KeywordIndex(texts);
        return new CorpusManifest(CorpusHash.tuple(identities.toArray(String[]::new)), profile, documents, bytes, skipped, texts.size());
    }

    public static UUID pointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.US_ASCII));
    }
}
