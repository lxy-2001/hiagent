package com.agentflow.web.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, String> {
    Optional<AgentSessionEntity> findByIdAndUserId(String id, String userId);

    @Query(value = "select count(*) from agent_session_context where session_id=:id and deleted_at is not null", nativeQuery = true)
    long deletionMarkerCount(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AgentSessionEntity session where session.id = :id and session.userId = :userId")
    Optional<AgentSessionEntity> findOwnedForUpdate(@Param("id") String id, @Param("userId") String userId);
}
