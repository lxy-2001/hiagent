package com.agentflow.web.agent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, String> {
}
