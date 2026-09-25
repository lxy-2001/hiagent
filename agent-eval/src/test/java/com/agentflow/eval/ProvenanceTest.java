package com.agentflow.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProvenanceTest {
    @TempDir Path root;
    private Path manifest(String path, String hash) throws Exception {
        var json = EvalJson.MAPPER.createObjectNode();
        json.put("schemaVersion", 1); json.put("codeSha", "a".repeat(40)); json.put("workingTreeDirty", false);
        json.put("trackedDiffHash", "b".repeat(64)); json.put("untrackedCount", 0);
        json.putArray("files").addObject().put("path", path).put("sha256", hash);
        Path file = root.resolve("code-provenance.json"); Files.writeString(file, json.toString()); return file;
    }
    @Test void validatesCopiedFilesBeforeAcceptingSourceIdentity() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "build");
        String hash = EvalJson.hash(Files.readAllBytes(root.resolve("pom.xml")));
        assertEquals("COPIED_SOURCE", CodeProvenance.read(root, manifest("pom.xml", hash), List.of("pom.xml")).status());
        Files.writeString(root.resolve("pom.xml"), "changed");
        assertThrows(IllegalArgumentException.class, () -> CodeProvenance.read(root, root.resolve("code-provenance.json"), List.of("pom.xml")));
    }
    @Test void rejectsTraversalSecretsMissingFilesAndFakeSha() throws Exception {
        for (String path : List.of("../pom.xml", ".env", "config/secret.txt", "missing.java")) {
            Path file = manifest(path, "b".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> CodeProvenance.read(root, file, List.of("pom.xml")));
        }
        Files.writeString(root.resolve("pom.xml"), "build");
        Path file = manifest("pom.xml", EvalJson.hash(Files.readAllBytes(root.resolve("pom.xml"))));
        var json = (tools.jackson.databind.node.ObjectNode) EvalJson.MAPPER.readTree(Files.readAllBytes(file));
        json.put("codeSha", "fake"); Files.writeString(file, json.toString());
        assertThrows(IllegalArgumentException.class, () -> CodeProvenance.read(root, file, List.of("pom.xml")));
    }
    @Test void malformedManifestDoesNotLeakParserInput() throws Exception {
        Path file = root.resolve("code-provenance.json");
        Files.writeString(file, "{PRIVATE_SENTINEL_007");
        var error = assertThrows(IllegalArgumentException.class, () -> CodeProvenance.read(root, file, List.of()));
        assertFalse(error.toString().contains("PRIVATE_SENTINEL_007"));
        assertNull(error.getCause());
    }
    @Test void noGitAndNoManifestIsUnknown() throws Exception {
        assertEquals("UNKNOWN", CodeProvenance.read(root, null, List.of()).status());
        assertNull(CodeProvenance.read(root, null, List.of()).codeSha());
    }
}
