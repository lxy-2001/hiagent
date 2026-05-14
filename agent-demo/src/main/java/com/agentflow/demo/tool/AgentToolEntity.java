package com.agentflow.demo.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_tool")
public class AgentToolEntity {

    @Id
    private String id;
    private String name;
    private String description;
    @Column(columnDefinition = "json")
    private String schemaJson;
    private boolean enabled;
    private String riskLevel;
    private Instant createdAt;
    private Instant updatedAt;

    protected AgentToolEntity() {
    }

    public AgentToolEntity(String id, String name, String description, String schemaJson,
                           boolean enabled, String riskLevel, Instant now) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.schemaJson = schemaJson;
        this.enabled = enabled;
        this.riskLevel = riskLevel;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
