package com.agentflow.web.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, String> {
}
