package com.agentflow.rag.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

class RagImportApplicationTest {
    @TempDir Path temporary;

    @Test
    void importsThroughRealCliAndBoundedHttpAdaptersThenReusesWithoutNetwork() throws Exception {
        var source=temporary.resolve("source"); Files.createDirectories(source);
        Files.writeString(source.resolve("java.md"),"Constructor injection declares dependencies.");
        var profile=new com.agentflow.rag.corpus.CorpusManifest.IndexProfile("test","fixed",3,800,100);
        var corpus=com.agentflow.rag.corpus.CorpusManifest.read(source,profile);
        var chunk=corpus.documents().get(0).chunks().get(0);
        try (var vector=new com.agentflow.rag.support.QdrantProtocolFixture();
             var embedding=new com.agentflow.rag.support.QdrantProtocolFixture()) {
            vector.enqueue(404,"{\"status\":\"missing\"}");
            vector.enqueue(404,"{\"status\":\"missing\"}");
            vector.enqueue(200,"{\"status\":\"ok\",\"result\":true}");
            vector.enqueue(200,"{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}");
            vector.enqueue(200,"{\"status\":\"ok\",\"result\":{\"count\":1}}");
            vector.enqueue(200,new tools.jackson.databind.json.JsonMapper().writeValueAsString(java.util.Map.of("status","ok","result",java.util.List.of(java.util.Map.of(
                    "id",com.agentflow.rag.corpus.CorpusManifest.pointId(chunk.chunkId()).toString(),
                    "payload",java.util.Map.of("snapshotId",corpus.snapshotId(),"chunkId",chunk.chunkId(),"contentHash",chunk.contentHash()))))));
            embedding.enqueue(200,"{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}");
            String[] args={"--agentflow.rag.command=import","--agentflow.rag.source-directory="+source,
                    "--agentflow.rag.store-directory="+temporary.resolve("store"),"--agentflow.rag.embedding-space-id=test",
                    "--agentflow.model.embedding-model=fixed","--agentflow.model.embedding-dimensions=3",
                    "--agentflow.model.embedding-base-url="+embedding.uri(),"--agentflow.model.embedding-api-key=fixture-only",
                    "--agentflow.rag.qdrant.base-url="+vector.uri()};
            var result=RagImportApplication.execute(args);
            assertThat(result.exitCode()).withFailMessage(result.toString()).isZero();
            assertThat(result.report().snapshotId()).isEqualTo(corpus.snapshotId());
            assertThat(RagImportApplication.execute(args).report().reused()).isTrue();
            assertThat(embedding.requests()).hasSize(1);
            assertThat(vector.requests()).hasSize(6);
        }
    }

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
