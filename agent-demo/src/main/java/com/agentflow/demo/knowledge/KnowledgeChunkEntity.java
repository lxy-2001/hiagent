package com.agentflow.demo.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunkEntity {

    @Id
    private String id;
    private String documentId;
    private int chunkNo;
    @Column(columnDefinition = "text")
    private String content;
    private String vectorId;
    private String contentHash;
    private Instant createdAt;

    protected KnowledgeChunkEntity() {
    }

    public KnowledgeChunkEntity(String id, String documentId, int chunkNo, String content,
                                String vectorId, String contentHash, Instant createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.chunkNo = chunkNo;
        this.content = content;
        this.vectorId = vectorId;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getVectorId() {
        return vectorId;
    }
}
