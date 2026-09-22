package com.agentflow.web.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConfirmedMemoryRepository extends JpaRepository<ConfirmedMemoryEntity, String> {
    Optional<ConfirmedMemoryEntity> findBySessionIdAndKey(String sessionId, String key);
    List<ConfirmedMemoryEntity> findBySessionId(String sessionId);
}
