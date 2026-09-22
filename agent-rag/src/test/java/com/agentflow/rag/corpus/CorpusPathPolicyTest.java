package com.agentflow.rag.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class CorpusPathPolicyTest {
    @TempDir Path root;
    private final CorpusPathPolicy policy = new CorpusPathPolicy();

    @Test
    void returnsSortedRelativeSourcesWithDefensiveBytes() throws Exception {
        Files.createDirectory(root.resolve("java"));
        Files.writeString(root.resolve("z.txt"), "z");
        Files.writeString(root.resolve("java/a.md"), "a");
        var sources = policy.read(root);
        assertThat(sources).extracting(CorpusPathPolicy.Source::relativePath).containsExactly("java/a.md", "z.txt");
        sources.get(0).bytes()[0] = 0;
        assertThat(sources.get(0).bytes()[0]).isEqualTo((byte) 'a');
    }

    @Test
    void rejectsUnsupportedFilesAndExcessiveCount() throws Exception {
        Files.writeString(root.resolve("x.json"), "{}");
        assertThatThrownBy(() -> policy.read(root)).hasMessage("UNSUPPORTED_SOURCE");
        Files.delete(root.resolve("x.json"));
        for (int i = 0; i < 101; i++) { Files.writeString(root.resolve(i + ".txt"), "a"); }
        assertThatThrownBy(() -> policy.read(root)).hasMessage("CORPUS_LIMIT");
    }

    @Test
    void rejectsOversizedFileAndNonDirectoryRoot() throws Exception {
        var file = root.resolve("large.txt");
        Files.write(file, new byte[1_048_577]);
        assertThatThrownBy(() -> policy.read(root)).hasMessage("CORPUS_LIMIT");
        assertThatThrownBy(() -> policy.read(file)).hasMessage("INVALID_PATH");
    }

    @Test
    void rejectsParentTraversalInsteadOfNormalizingItAway() {
        assertThatThrownBy(() -> policy.read(root.resolve("../" + root.getFileName()))).hasMessage("INVALID_PATH");
    }

    @Test
    void rejectsAggregateSizeAndNfcNameCollisions() throws Exception {
        byte[] block = new byte[1_048_576];
        for (int i = 0; i < 21; i++) { Files.write(root.resolve(i + ".txt"), block); }
        assertThatThrownBy(() -> policy.read(root)).hasMessage("CORPUS_LIMIT");
        for (int i = 0; i < 21; i++) { Files.delete(root.resolve(i + ".txt")); }
        Files.writeString(root.resolve("é.md"), "first");
        Files.writeString(root.resolve("e\u0301.md"), "second");
        assertThatThrownBy(() -> policy.read(root)).hasMessage("INVALID_PATH");
    }

    @Test
    void rejectsDirectoryLinksIncludingWindowsJunctions() throws Exception {
        Path target = Files.createTempDirectory(root.getParent(), "corpus-link-target-");
        Path link = root.resolve("linked");
        try {
            Files.writeString(target.resolve("outside.md"), "outside");
            if (System.getProperty("os.name").startsWith("Windows")) {
                var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.waitFor()).as(output).isZero();
            } else { Files.createSymbolicLink(link, target); }
            assertThatThrownBy(() -> policy.read(root)).hasMessage("INVALID_PATH");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target.resolve("outside.md"));
            Files.deleteIfExists(target);
        }
    }
}
