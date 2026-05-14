package com.agentflow.demo.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, String> {

    List<KnowledgeChunkEntity> findByVectorIdIn(Collection<String> vectorIds);
}
