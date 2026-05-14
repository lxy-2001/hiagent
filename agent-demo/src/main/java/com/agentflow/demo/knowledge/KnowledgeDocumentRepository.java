package com.agentflow.demo.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, String> {

    Optional<KnowledgeDocumentEntity> findByContentHash(String contentHash);
}
