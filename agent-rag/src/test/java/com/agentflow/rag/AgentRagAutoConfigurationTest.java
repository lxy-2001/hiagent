package com.agentflow.rag;

import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.rag.RagRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.core.rag.RetrievalPayload;
import com.agentflow.core.rag.RetrievalRequest;
import com.agentflow.core.runtime.ToolExecutionControl;
import com.agentflow.rag.corpus.CorpusSnapshotStore;
import com.agentflow.rag.qdrant.VectorIndex;
import com.agentflow.tool.AgentToolAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRagAutoConfigurationTest {

    @Test
    void doesNotCreateRagRetrieverWithoutAnApplicationImplementation() {
        runner().run(context -> assertThat(context).doesNotHaveBean(RagRetriever.class));
    }

    @Test
    void keepsApplicationRetrieverAsTheOnlyCandidate() {
        RagRetriever custom = (query, limit) -> List.of(
                new RagDocument("doc-1", "test", "content", 1.0d));

        runner().withBean(RagRetriever.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(RagRetriever.class);
            assertThat(context).getBean(RagRetriever.class).isSameAs(custom);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRagAutoConfiguration.class));
    }

    @Test
    void enabledCustomEvidenceRetrieverNeedsNoDefaultNetworkOrStore() {
        RagRetriever retriever = new RagRetriever() {
            @Override public boolean supportsEvidence() { return true; }
            @Override public List<RagDocument> retrieve(String query, int limit) { return List.of(); }
            @Override public RetrievalPayload retrieve(RetrievalRequest request, ToolExecutionControl control) {
                return new RetrievalPayload("a".repeat(64), RetrievalPayload.Mode.HYBRID, RetrievalPayload.SemanticState.OK,
                        RetrievalPayload.KeywordState.OK, null, RetrievalPayload.EmptyReason.NO_MATCH, false, 0, List.of());
            }
        };
        runner().withConfiguration(AutoConfigurations.of(AgentToolAutoConfiguration.class))
                .withPropertyValues("agentflow.rag.enabled=true").withBean(RagRetriever.class, () -> retriever).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(KnowledgeSearchTool.class);
                    assertThat(context).doesNotHaveBean(CorpusSnapshotStore.class).doesNotHaveBean(VectorIndex.class);
                    assertThat(context.getBean(ToolRegistry.class).enabledToolNames()).contains("knowledge.search");
                });
    }

    @Test
    void enabledMissingConfigurationAndLegacyRetrieverFailClearly() {
        runner().withPropertyValues("agentflow.rag.enabled=true").run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("agentflow.rag.enabled=true")
                .withBean(RagRetriever.class, () -> (query, limit) -> List.of())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledCustomRegistryMustActuallyExposeSearch() {
        var retriever = org.mockito.Mockito.mock(RagRetriever.class);
        org.mockito.Mockito.when(retriever.supportsEvidence()).thenReturn(true);
        runner().withPropertyValues("agentflow.rag.enabled=true")
                .withBean(RagRetriever.class, () -> retriever)
                .withBean(ToolRegistry.class, com.agentflow.tool.InMemoryToolRegistry::new)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void closesDefaultStoreWhenEmbeddingCapabilityIsMissing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var source = root.resolve("source");
        java.nio.file.Files.createDirectories(source);
        java.nio.file.Files.writeString(source.resolve("java.md"), "Java runtime");
        var profile = new com.agentflow.rag.corpus.CorpusManifest.IndexProfile("test", "fixed", 3, 800, 100);
        var manifest = com.agentflow.rag.corpus.CorpusManifest.read(source, profile);
        var storePath = root.resolve("store");
        try (var store = new CorpusSnapshotStore(storePath)) {
            store.prepare(manifest); store.complete(manifest); store.activate(manifest.snapshotId());
        }
        runner().withConfiguration(AutoConfigurations.of(AgentToolAutoConfiguration.class))
                .withPropertyValues("agentflow.rag.enabled=true", "agentflow.rag.store-directory=" + storePath,
                        "agentflow.rag.embedding-space-id=test", "agentflow.model.embedding-model=fixed", "agentflow.model.embedding-dimensions=3")
                .withBean(com.agentflow.core.model.EmbeddingClient.class, () -> text -> List.of(1d, 2d, 3d))
                .run(context -> assertThat(context).hasFailed());
        try (var reopened = new CorpusSnapshotStore(storePath)) {
            assertThat(reopened.loadActive(profile).snapshotId()).isEqualTo(manifest.snapshotId());
        }
    }
}
