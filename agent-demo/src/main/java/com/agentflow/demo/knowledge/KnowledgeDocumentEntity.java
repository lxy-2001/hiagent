package com.agentflow.demo.knowledge;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity {

    @Id
    private String id;
    private String title;
    private String source;
    private String contentHash;
    private Instant createdAt;
    private Instant updatedAt;

    protected KnowledgeDocumentEntity() {
    }

    public KnowledgeDocumentEntity(String id, String title, String source, String contentHash, Instant now) {
        this.id = id;
        this.title = title;
        this.source = source;
        this.contentHash = contentHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }
}
