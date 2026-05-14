package com.agentflow.web.agent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_session")
public class AgentSessionEntity {

    @Id
    private String id;
    private String userId;
    private String title;
    private Instant createdAt;

    protected AgentSessionEntity() {
    }

    public AgentSessionEntity(String id, String userId, String title, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }
}
