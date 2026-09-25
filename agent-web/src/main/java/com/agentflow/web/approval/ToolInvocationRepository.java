package com.agentflow.web.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, String> {
    List<ToolInvocationEntity> findByTaskIdOrderByCreatedAtAscIdAsc(String taskId);
    Optional<ToolInvocationEntity> findByTaskIdAndCallId(String taskId, String callId);
}
