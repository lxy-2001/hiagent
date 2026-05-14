package com.agentflow.demo.tool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentToolRepository extends JpaRepository<AgentToolEntity, String> {

    Optional<AgentToolEntity> findByName(String name);
}
