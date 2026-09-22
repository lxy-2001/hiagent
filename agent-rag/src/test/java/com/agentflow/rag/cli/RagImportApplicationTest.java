package com.agentflow.rag.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

class RagImportApplicationTest {
    @TempDir Path temporary;

    @Test
    void noCommandIsUsageAndNeverImports() {
        var result = RagImportApplication.execute();
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.report().errorCode()).isEqualTo("USAGE");
    }

    @Test
    void validatesWithoutApiKeyOrAnyNetworkAndLeavesNoActiveStore() throws Exception {
        var source = temporary.resolve("source"); Files.createDirectories(source);
        Files.writeString(source.resolve("java.md"), "# Java\n\nConstructor injection declares dependencies.");
        var result = RagImportApplication.execute("--agentflow.rag.command=validate",
                "--agentflow.rag.source-directory=" + source,
                "--agentflow.rag.store-directory=" + temporary.resolve("store"),
                "--agentflow.rag.embedding-space-id=test",
                "--agentflow.model.embedding-model=fixed", "--agentflow.model.embedding-dimensions=3",
                "--agentflow.model.embedding-base-url=http://127.0.0.1:1",
                "--agentflow.rag.qdrant.base-url=http://127.0.0.1:1");
        assertThat(result.exitCode()).isZero();
        assertThat(result.report().outcome()).isEqualTo("SUCCESS");
        assertThat(result.report().chunkCount()).isEqualTo(1);
        assertThat(temporary.resolve("store")).doesNotExist();
        assertThat(result.report().toString()).doesNotContain(source.toString(), "Constructor injection");
    }

    @Test
    void rejectsNestedSourceAndStoreAndUnknownCommands() throws Exception {
        var source = temporary.resolve("source"); Files.createDirectories(source);
        Files.writeString(source.resolve("java.md"), "Java");
        var result = RagImportApplication.execute("--agentflow.rag.command=validate",
                "--agentflow.rag.source-directory=" + source,
                "--agentflow.rag.store-directory=" + source.resolve("store"),
                "--agentflow.rag.embedding-space-id=test");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.report().errorCode()).isEqualTo("INVALID_PATH");
        assertThat(RagImportApplication.execute("--agentflow.rag.command=upload").exitCode()).isEqualTo(2);
    }
}
