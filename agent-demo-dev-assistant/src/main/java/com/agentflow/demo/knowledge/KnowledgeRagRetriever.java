package com.agentflow.demo.knowledge;

import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.core.rag.RagDocument;
import com.agentflow.core.rag.RagRetriever;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Primary
public class KnowledgeRagRetriever implements RagRetriever {

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeRagRetriever(EmbeddingClient embeddingClient, QdrantClient qdrantClient,
                                 KnowledgeChunkRepository chunkRepository) {
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.chunkRepository = chunkRepository;
    }

    @Override
    public List<RagDocument> retrieve(String query, int limit) {
        try {
            List<QdrantClient.SearchHit> hits = qdrantClient.search(embeddingClient.embed(query), limit);
            if (!hits.isEmpty()) {
                Map<String, KnowledgeChunkEntity> chunks = chunkRepository.findByVectorIdIn(
                                hits.stream().map(QdrantClient.SearchHit::vectorId).toList())
                        .stream()
                        .collect(Collectors.toMap(KnowledgeChunkEntity::getVectorId, Function.identity()));
                return hits.stream()
                        .map(hit -> toDocument(hit, chunks.get(hit.vectorId())))
                        .filter(doc -> doc != null)
                        .toList();
            }
        } catch (RuntimeException ignored) {
            // Keep the demo usable before Qdrant is started or before a real embedding key is configured.
        }
        return keywordFallback(query, limit);
    }

    private RagDocument toDocument(QdrantClient.SearchHit hit, KnowledgeChunkEntity chunk) {
        if (chunk == null) {
            return null;
        }
        return new RagDocument(chunk.getId(), "knowledge:" + chunk.getId(), chunk.getContent(), hit.score());
    }

    private List<RagDocument> keywordFallback(String query, int limit) {
        String normalized = query == null ? "" : query.toLowerCase();
        List<String> terms = List.of("秒杀", "库存", "redis", "mysql", "接口", "并发", "一致性");
        List<RagDocument> documents = new ArrayList<>();
        for (KnowledgeChunkEntity chunk : chunkRepository.findAll()) {
            long score = terms.stream()
                    .filter(term -> normalized.contains(term.toLowerCase()) && chunk.getContent().toLowerCase().contains(term.toLowerCase()))
                    .count();
            if (score > 0) {
                documents.add(new RagDocument(chunk.getId(), "knowledge:" + chunk.getId(), chunk.getContent(), score));
            }
        }
        return documents.stream()
                .sorted(Comparator.comparingDouble(RagDocument::score).reversed())
                .limit(limit)
                .toList();
    }
}
