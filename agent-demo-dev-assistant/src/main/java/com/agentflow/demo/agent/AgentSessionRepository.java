package com.agentflow.demo.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, String> {
}
