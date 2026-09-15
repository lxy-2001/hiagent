package com.agentflow.web.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, String> {

    List<AgentStepEntity> findByTaskIdOrderByStepNoAsc(String taskId);

    List<AgentStepEntity> findTop256ByTaskIdOrderByStepNoAsc(String taskId);

    void deleteByTaskId(String taskId);

    Optional<AgentStepEntity> findByTaskIdAndStepNo(String taskId, int stepNo);
}
