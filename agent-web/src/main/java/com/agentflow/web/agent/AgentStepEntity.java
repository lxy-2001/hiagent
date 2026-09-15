package com.agentflow.web.agent;

import com.agentflow.core.AgentStepRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import com.agentflow.web.run.RunResultProjector;

@Entity
@Table(name = "agent_step", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_step_task_no", columnNames = {"task_id", "step_no"}))
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
    private String decisionId;
    private String callId;
    private String errorCode;
    private boolean terminal;
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
        this.decisionId = record.decisionId();
        this.callId = record.callId();
        this.errorCode = record.errorCode();
        this.terminal = record.terminal();
        this.createdAt = Instant.now();
    }

    public AgentStepEntity(RunResultProjector.ProjectedStep step, Instant createdAt) {
        this.id = UUID.randomUUID().toString();
        apply(step);
        this.createdAt = createdAt;
    }

    public void apply(RunResultProjector.ProjectedStep step) {
        Objects.requireNonNull(step, "step must not be null");
        this.taskId = step.taskId();
        this.stepNo = step.stepNo();
        this.stepType = step.stepType();
        this.toolName = step.name();
        this.input = step.input();
        this.output = step.output();
        this.status = step.status();
        this.latencyMs = step.latencyMs();
        this.promptTokens = step.promptTokens();
        this.completionTokens = step.completionTokens();
        this.errorMessage = step.errorMessage();
        this.decisionId = step.decisionId();
        this.callId = step.callId();
        this.errorCode = step.errorCode();
        this.terminal = step.terminal();
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

    public String getTaskId() {
        return taskId;
    }

    public String getInput() {
        return input;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public String getDecisionId() {
        return decisionId;
    }

    public String getCallId() {
        return callId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public RunResultProjector.ProjectedStep toProjection() {
        return new RunResultProjector.ProjectedStep(taskId, stepNo, stepType, toolName,
                input, output, status, latencyMs, promptTokens, completionTokens,
                errorMessage, decisionId, callId, errorCode, terminal);
    }
}
