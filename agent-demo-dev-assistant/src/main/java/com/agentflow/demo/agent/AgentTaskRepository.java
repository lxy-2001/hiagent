package com.agentflow.demo.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, String> {
}
