package com.agentflow.rag;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import com.agentflow.core.rag.RagRetriever;
import com.agentflow.core.model.DeadlineAwareEmbeddingClient;
import com.agentflow.core.tool.ToolRegistry;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.rag.corpus.CorpusManifest;
import com.agentflow.rag.corpus.CorpusSnapshot;
import com.agentflow.rag.corpus.CorpusSnapshotStore;
import com.agentflow.rag.qdrant.VectorIndex;
import com.agentflow.rag.qdrant.QdrantVectorIndex;
import com.agentflow.rag.retrieval.HybridRagRetriever;
import java.nio.file.Path;
import java.net.URI;

@AutoConfiguration
@AutoConfigureAfter(name = "com.agentflow.llm.AgentLlmAutoConfiguration")
@AutoConfigureBefore(name = "com.agentflow.tool.AgentToolAutoConfiguration")
@EnableConfigurationProperties(RagProperties.class)
public class AgentRagAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "agentflow.rag", name = "enabled", havingValue = "true")
    static class Enabled {
        @Bean
        @ConditionalOnMissingBean(KnowledgeSearchTool.class)
        KnowledgeSearchTool knowledgeSearchTool(RagRetriever retriever) { return new KnowledgeSearchTool(retriever); }

        @Bean
        SmartInitializingSingleton verifyKnowledgeTool(ObjectProvider<ToolRegistry> registry) {
            return () -> {
                ToolRegistry actual = registry.getIfAvailable();
                if (actual == null || !actual.enabledToolNames().contains("knowledge.search")) {
                    throw new IllegalStateException("RAG_CONFIGURATION_INVALID: knowledge.search is not registered");
                }
            };
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnMissingBean(RagRetriever.class)
        @EnableConfigurationProperties(AgentFlowProperties.class)
        static class DefaultRetriever {
            @Bean(destroyMethod = "close")
            @ConditionalOnMissingBean(CorpusSnapshotStore.class)
            CorpusSnapshotStore corpusSnapshotStore(RagProperties properties) {
                if (properties.getStoreDirectory() == null || properties.getStoreDirectory().isBlank()) {
                    throw new IllegalStateException("RAG_CONFIGURATION_INVALID: store directory required");
                }
                return new CorpusSnapshotStore(Path.of(properties.getStoreDirectory()));
            }

            @Bean
            CorpusSnapshot corpusSnapshot(CorpusSnapshotStore store, RagProperties rag, AgentFlowProperties model) {
                return store.loadActive(new CorpusManifest.IndexProfile(rag.getEmbeddingSpaceId(), model.model().getEmbeddingModel(),
                        model.model().getEmbeddingDimensions(), rag.getChunkSize(), rag.getOverlap()));
            }

            @Bean
            @ConditionalOnMissingBean(VectorIndex.class)
            QdrantVectorIndex qdrantVectorIndex(CorpusSnapshotStore store, RagProperties properties) {
                return new QdrantVectorIndex(URI.create(properties.getQdrant().getBaseUrl()), properties.getQdrant().getApiKey(), store.storeId());
            }

            @Bean
            HybridRagRetriever ragRetriever(CorpusSnapshot snapshot, DeadlineAwareEmbeddingClient embedding,
                                           VectorIndex vectors, RagProperties properties) {
                return new HybridRagRetriever(snapshot, embedding, vectors, properties.isAllowKeywordFallback());
            }
        }
    }
}
