package com.agentflow.demo.agent;

import com.agentflow.core.AgentStepRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_step")
public class AgentStepEntity {

    @Id
    private String id;
    private String taskId;
    private int stepNo;
    private String stepType;
    private String toolName;
    @Column(columnDefinition = "longtext")
    private String input;
    @Column(columnDefinition = "longtext")
    private String output;
    private String status;
    private long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    @Column(columnDefinition = "text")
    private String errorMessage;
    private Instant createdAt;

    protected AgentStepEntity() {
    }

    public AgentStepEntity(AgentStepRecord record) {
        this.id = UUID.randomUUID().toString();
        this.taskId = record.taskId();
        this.stepNo = record.stepNo();
        this.stepType = record.stepType().name();
        this.toolName = record.toolName();
        this.input = record.input();
        this.output = record.output();
        this.status = record.status().name();
        this.latencyMs = record.latencyMs();
        this.promptTokens = record.promptTokens();
        this.completionTokens = record.completionTokens();
        this.errorMessage = record.errorMessage();
        this.createdAt = Instant.now();
    }

    public int getStepNo() {
        return stepNo;
    }

    public String getStepType() {
        return stepType;
    }

    public String getToolName() {
        return toolName;
    }

    public String getOutput() {
        return output;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
