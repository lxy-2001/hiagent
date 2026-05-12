package com.agentflow.demo.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, String> {

    List<AgentStepEntity> findByTaskIdOrderByStepNoAsc(String taskId);
}
