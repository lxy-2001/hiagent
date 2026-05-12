package com.agentflow.demo.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_task")
public class AgentTaskEntity {

    @Id
    private String id;
    private String sessionId;
    private String userId;
    @Column(columnDefinition = "text")
    private String userInput;
    private String status;
    @Column(columnDefinition = "longtext")
    private String finalAnswer;
    private Instant createdAt;
    private Instant updatedAt;

    protected AgentTaskEntity() {
    }

    public AgentTaskEntity(String id, String sessionId, String userId, String userInput, String status, Instant now) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.userInput = userInput;
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserInput() {
        return userInput;
    }

    public String getStatus() {
        return status;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void complete(String finalAnswer) {
        this.status = "SUCCEEDED";
        this.finalAnswer = finalAnswer;
        this.updatedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = "FAILED";
        this.finalAnswer = message;
        this.updatedAt = Instant.now();
    }
}
