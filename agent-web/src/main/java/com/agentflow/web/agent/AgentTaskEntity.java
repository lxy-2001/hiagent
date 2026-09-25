package com.agentflow.web.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import com.agentflow.core.chat.TokenUsage;
import com.agentflow.core.runtime.TerminationReason;
import com.agentflow.web.run.RunLifecycleStatus;
import com.agentflow.web.run.RunResultProjector;

@Entity
@Table(name = "agent_task")
public class AgentTaskEntity {

    @Id
    private String id;
    private String sessionId;
    private long turnSequence;
    private String userId;
    @Column(columnDefinition = "text")
    private String userInput;
    private String status;
    @Column(columnDefinition = "longtext")
    private String finalAnswer;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private boolean cancelRequested;
    private String terminationReason;
    private String runtimeReason;
    private String errorCode;
    private boolean recordingComplete;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private boolean requireEvidence;
    @Column(columnDefinition = "longtext")
    private String citationsJson;

    public boolean isRequireEvidence() { return requireEvidence; }
    public String getCitationsJson() { return citationsJson; }

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

    public AgentTaskEntity(String id, String sessionId, String userId, String userInput, String status,
                           Instant now, long turnSequence) {
        this(id, sessionId, userId, userInput, status, now);
        if (turnSequence < 1) throw new IllegalArgumentException("turnSequence must be positive");
        this.turnSequence = turnSequence;
    }

    public AgentTaskEntity(String id, String sessionId, String userId, String userInput, String status,
                           Instant now, long turnSequence, boolean requireEvidence) {
        this(id, sessionId, userId, userInput, status, now, turnSequence);
        this.requireEvidence = requireEvidence;
    }

    public long getTurnSequence() { return turnSequence; }

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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public String getRuntimeReason() {
        return runtimeReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRecordingComplete() {
        return recordingComplete;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void waitForApproval(Instant at) {
        if (!RunLifecycleStatus.RUNNING.name().equals(status) || cancelRequested)
            throw new IllegalStateException("run cannot wait for approval");
        status = RunLifecycleStatus.WAITING_APPROVAL.name(); updatedAt = at;
    }

    public void resumeFromApproval(Instant at) {
        if (!RunLifecycleStatus.WAITING_APPROVAL.name().equals(status) || cancelRequested)
            throw new IllegalStateException("run cannot resume approval");
        status = RunLifecycleStatus.RUNNING.name(); updatedAt = at;
    }

    public void markRunning(Instant startedAt) {
        if (startedAt == null || startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt must not be before createdAt");
        }
        if (!"QUEUED".equals(status)) {
            throw new IllegalStateException("only queued task can start");
        }
        this.status = "RUNNING";
        this.startedAt = startedAt;
        this.updatedAt = startedAt;
    }

    public void applyFinal(RunResultProjector.FinalProjection projection) {
        if (!id.equals(projection.taskId())) {
            throw new IllegalArgumentException("projection belongs to another task");
        }
        this.citationsJson = projection.status() == RunLifecycleStatus.SUCCEEDED
                ? new com.agentflow.web.run.CitationSnapshotCodec().encode(projection.citations()) : null;
        this.status = projection.status().name();
        this.finalAnswer = projection.finalAnswer();
        this.finishedAt = projection.finishedAt();
        this.cancelRequested |= projection.cancelRequested();
        this.terminationReason = projection.terminationReason().name();
        this.runtimeReason = projection.runtimeReason() == null
                ? null : projection.runtimeReason().name();
        this.errorCode = projection.errorCode();
        this.recordingComplete = projection.recordingComplete();
        TokenUsage usage = projection.usage();
        this.promptTokens = usage == null ? null : usage.promptTokens();
        this.completionTokens = usage == null ? null : usage.completionTokens();
        this.totalTokens = usage == null ? null : usage.totalTokens();
        this.updatedAt = projection.finishedAt();
    }

    public void requestCancellation(Instant now) {
        if (!RunLifecycleStatus.valueOf(status).isTerminal()) {
            cancelRequested = true;
            updatedAt = now;
        }
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
