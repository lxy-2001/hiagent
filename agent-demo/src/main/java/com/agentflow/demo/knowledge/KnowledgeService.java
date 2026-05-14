package com.agentflow.demo.knowledge;

import com.agentflow.core.model.EmbeddingClient;
import com.agentflow.llm.AgentFlowProperties;
import com.agentflow.web.support.Hashing;
import com.agentflow.web.support.Ids;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeService {

    private final ResourcePatternResolver resourcePatternResolver;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final AgentFlowProperties properties;

    public KnowledgeService(ResourcePatternResolver resourcePatternResolver,
                            KnowledgeDocumentRepository documentRepository,
                            KnowledgeChunkRepository chunkRepository,
                            EmbeddingClient embeddingClient,
                            QdrantClient qdrantClient,
                            AgentFlowProperties properties) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    @Transactional
    public ReloadResult reloadBuiltInKnowledge() {
        List<String> imported = new ArrayList<>();
        try {
            if (qdrantClient.isAvailable()) {
                qdrantClient.ensureCollection(properties.model().getEmbeddingDimensions());
            }
            Resource[] resources = resourcePatternResolver.getResources("classpath:/knowledge/*.md");
            for (Resource resource : resources) {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                String hash = Hashing.sha256Hex(content);
                if (documentRepository.findByContentHash(hash).isPresent()) {
                    continue;
                }
                Instant now = Instant.now();
                String documentId = Ids.newId();
                String filename = resource.getFilename() == null ? "knowledge.md" : resource.getFilename();
                documentRepository.save(new KnowledgeDocumentEntity(documentId, titleOf(content, filename),
                        "classpath:/knowledge/" + filename, hash, now));
                List<String> chunks = chunk(content);
                for (int i = 0; i < chunks.size(); i++) {
                    String chunkContent = chunks.get(i);
                    String vectorId = Ids.newId();
                    chunkRepository.save(new KnowledgeChunkEntity(Ids.newId(), documentId, i + 1, chunkContent,
                            vectorId, Hashing.sha256Hex(chunkContent), now));
                    if (qdrantClient.isAvailable()) {
                        qdrantClient.upsert(vectorId, embeddingClient.embed(chunkContent),
                                Map.of("documentId", documentId, "source", filename));
                    }
                }
                imported.add(filename);
            }
            return new ReloadResult(imported.size(), imported);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load built-in knowledge", ex);
        }
    }

    private String titleOf(String content, String fallback) {
        return content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(fallback);
    }

    private List<String> chunk(String content) {
        String[] blocks = content.split("\\n\\s*\\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (current.length() + block.length() > 1200 && current.length() > 0) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(block).append("\n\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    public record ReloadResult(int importedCount, List<String> importedFiles) {
    }
}
