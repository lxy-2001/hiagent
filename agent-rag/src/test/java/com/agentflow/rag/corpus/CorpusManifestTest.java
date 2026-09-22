package com.agentflow.rag.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class CorpusManifestTest {
    @TempDir Path root;
    private final CorpusManifest.IndexProfile profile = new CorpusManifest.IndexProfile("test", "fixed", 3, 800, 100);

    @Test
    void identitiesAreStableIncludeEmptyFilesAndChangeForDeletionAndProfile() throws Exception {
        Files.writeString(root.resolve("a.md"), "Java");
        Files.writeString(root.resolve("b.md"), "Java");
        Files.writeString(root.resolve("empty.txt"), " \n");
        var first = CorpusManifest.read(root, profile);
        assertThat(CorpusManifest.read(root, profile)).isEqualTo(first);
        assertThat(first.chunkCount()).isEqualTo(2);
        assertThat(first.skippedEmptyCount()).isEqualTo(1);
        assertThat(first.documents().get(0).chunks().get(0).chunkId())
                .isNotEqualTo(first.documents().get(1).chunks().get(0).chunkId());
        Files.delete(root.resolve("empty.txt"));
        assertThat(CorpusManifest.read(root, profile).snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(CorpusManifest.read(root, new CorpusManifest.IndexProfile("other", "fixed", 3, 800, 100)).snapshotId())
                .isNotEqualTo(CorpusManifest.read(root, profile).snapshotId());
    }

    @Test
    void rejectsEmptyCorpusAndMoreThanTenThousandChunksAsAWhole() throws Exception {
        Files.writeString(root.resolve("empty.txt"), " \n");
        assertThatThrownBy(() -> CorpusManifest.read(root, profile)).hasMessage("EMPTY_CORPUS");
        String text = "a".repeat(1_048_576);
        for (int i = 0; i < 7; i++) { Files.writeString(root.resolve(i + ".txt"), text); }
        assertThatThrownBy(() -> CorpusManifest.read(root, profile)).hasMessage("CORPUS_LIMIT");
    }
}
