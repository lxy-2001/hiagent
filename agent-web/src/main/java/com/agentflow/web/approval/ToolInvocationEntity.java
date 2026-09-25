package com.agentflow.web.approval;

import com.agentflow.core.approval.ApprovalStatus;
import com.agentflow.core.tool.ToolInvocationRecord;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable invocation facts. Raw arguments and remote responses never enter this entity. */
@Entity
@Table(name = "agent_tool_invocation", uniqueConstraints = @UniqueConstraint(columnNames = {"task_id", "call_id"}))
public class ToolInvocationEntity {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String taskId;
    @Column(nullable = false, length = 128) private String callId;
    @Column(nullable = false, length = 36) private String ownerId;
    @Column(nullable = false, length = 100) private String toolName;
    @Column(length = 32) private String serverId;
    @Column(length = 64) private String remoteToolName;
    @Column(nullable = false, length = 64) private String definitionVersion;
    @Column(nullable = false, length = 64) private String policyVersion;
    @Column(nullable = false, length = 64) private String argumentsDigest;
    @Column(nullable = false, length = 16) private String risk;
    @Column(nullable = false, length = 32) private String policyAction;
    @Column(nullable = false, length = 16) private String effect;
    @Column(length = 256) private String actionSummary;
    @Column(columnDefinition = "text") private String argumentPreviewJson;
    @Column(length = 16) private String approvalStatus;
    @Column(nullable = false) private Instant createdAt;
    private Instant expiresAt;
    private Instant decidedAt;
    @Column(length = 16) private String decisionSource;
    private int dispatchCount;
    private Instant dispatchAt;
    @Column(nullable = false, length = 16) private String outcome;
    @Column(length = 64) private String errorCode;
    private Long approvalWaitMs;
    private Long executionMs;

    protected ToolInvocationEntity() { }

    public static ToolInvocationEntity completed(String taskId, String ownerId, ToolInvocationRecord record) {
        if (record.approvalId() != null) throw new IllegalArgumentException("approval must already exist");
        ToolInvocationEntity row = new ToolInvocationEntity();
        row.id = UUID.randomUUID().toString();
        row.bind(taskId, ownerId, record);
        row.execution(record);
        return row;
    }

    public static ToolInvocationEntity pending(com.agentflow.core.approval.ApprovalRequest request, String ownerId) {
        var prepared = request.preparedCall();
        var policy = request.policyDecision();
        String name = prepared.call().name();
        String[] remote = name.startsWith("mcp.") ? name.split("\\.", 3) : null;
        var fact = new ToolInvocationRecord("006-v1", prepared.runId(), prepared.callId(), name,
                remote == null ? null : remote[1], remote == null ? null : remote[2],
                prepared.toolDefinitionVersion(), policy.policyVersion(), prepared.argumentsDigest(), policy.risk(),
                policy.effect(), policy.action(), prepared.createdAt(), null, null, null, null, 0, null,
                ToolInvocationRecord.Outcome.NOT_DISPATCHED, null);
        ToolInvocationEntity row = new ToolInvocationEntity();
        row.bind(prepared.runId(), ownerId, fact);
        row.id = request.approvalId().toString();
        row.execution(fact);
        row.approvalStatus = ApprovalStatus.PENDING.name();
        row.expiresAt = request.expiresAt();
        var textPolicy = new com.agentflow.core.context.ContextTextPolicy();
        row.actionSummary = textPolicy.sanitizeInput(policy.actionSummary());
        var previews = new java.util.ArrayList<ApprovalSnapshot.ArgumentPreview>();
        for (var entry : prepared.call().arguments().values().entrySet()) {
            String key = entry.getKey();
            if (key.length() > 64) throw new IllegalArgumentException("preview name too long");
            boolean visible = policy.visibleArgumentNames().contains(key)
                    && !key.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|token|api.?key|credential).*");
            String value = visible ? textPolicy.sanitizeInput(String.valueOf(entry.getValue())) : "[masked]";
            boolean truncated = value.length() > 256;
            if (truncated) value = value.substring(0, Character.isHighSurrogate(value.charAt(255)) ? 255 : 256);
            previews.add(new ApprovalSnapshot.ArgumentPreview(key, value, !visible, truncated));
        }
        row.argumentPreviewJson = new tools.jackson.databind.ObjectMapper().writeValueAsString(previews);
        if (row.argumentPreviewJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("approval preview exceeds limit");
        return row;
    }

    public void resolve(ApprovalStatus status, com.agentflow.core.approval.ApprovalResolution.DecisionSource source,
                        Instant at, long waitMillis) {
        if (!ApprovalStatus.PENDING.name().equals(approvalStatus)) return;
        if (status == ApprovalStatus.PENDING || source == null || at == null || waitMillis < 0)
            throw new IllegalArgumentException("invalid resolution");
        approvalStatus = status.name(); decisionSource = source.name(); decidedAt = at; approvalWaitMs = waitMillis;
    }

    public void terminatePending(ApprovalStatus status, com.agentflow.core.approval.ApprovalResolution.DecisionSource source,
                                 Instant at) {
        if (!ApprovalStatus.PENDING.name().equals(approvalStatus)) return;
        if (status == ApprovalStatus.PENDING || status == ApprovalStatus.APPROVED || status == ApprovalStatus.REJECTED)
            throw new IllegalArgumentException("invalid system termination");
        approvalStatus = status.name(); decisionSource = source.name(); decidedAt = at;
        // No monotonic measurement survives a process loss; null is deliberately not fabricated as zero.
    }

    public void markDispatch(Instant at) {
        if (!ApprovalStatus.APPROVED.name().equals(approvalStatus) || dispatchCount != 0)
            throw new IllegalStateException("approval cannot dispatch");
        dispatchCount = 1; dispatchAt = at; outcome = ToolInvocationRecord.Outcome.UNKNOWN.name();
    }

    public ApprovalSnapshot snapshot() {
        if (approvalStatus == null) throw new IllegalStateException("not an approval");
        var preview = new tools.jackson.databind.ObjectMapper().readValue(argumentPreviewJson,
                ApprovalSnapshot.ArgumentPreview[].class);
        return new ApprovalSnapshot(id, taskId, taskId, callId, toolName, serverId, remoteToolName,
                com.agentflow.core.tool.RiskLevel.valueOf(risk), actionSummary, java.util.List.of(preview),
                argumentsDigest, definitionVersion, policyVersion, ApprovalStatus.valueOf(approvalStatus),
                createdAt, expiresAt, decidedAt, decisionSource == null ? null :
                com.agentflow.core.approval.ApprovalResolution.DecisionSource.valueOf(decisionSource),
                dispatchCount, ToolInvocationRecord.Outcome.valueOf(outcome), approvalWaitMs);
    }

    private void bind(String taskId, String ownerId, ToolInvocationRecord record) {
        UUID.fromString(taskId);
        if (!taskId.equals(record.runId()) || ownerId == null || ownerId.isBlank() || ownerId.length() > 36)
            throw new IllegalArgumentException("invalid invocation ownership");
        this.taskId = taskId; this.ownerId = ownerId;
        callId = record.callId(); toolName = record.toolName(); serverId = record.serverId();
        remoteToolName = record.remoteToolName(); definitionVersion = record.definitionVersion();
        policyVersion = record.policyVersion(); argumentsDigest = record.argumentsDigest();
        risk = record.risk().name(); effect = record.effect().name(); policyAction = record.policyAction().name();
        createdAt = record.createdAt();
    }

    /** Merge execution facts only; a confirmed approval decision is never overwritten. */
    public void merge(ToolInvocationRecord record) {
        if (!taskId.equals(record.runId()) || !callId.equals(record.callId())
                || !toolName.equals(record.toolName()) || !Objects.equals(serverId, record.serverId())
                || !Objects.equals(remoteToolName, record.remoteToolName())
                || !definitionVersion.equals(record.definitionVersion()) || !policyVersion.equals(record.policyVersion())
                || !argumentsDigest.equals(record.argumentsDigest()) || !effect.equals(record.effect().name())
                || !risk.equals(record.risk().name()) || !policyAction.equals(record.policyAction().name())
                || !createdAt.equals(record.createdAt())
                || (approvalStatus == null ? record.approvalId() != null : !id.equals(String.valueOf(record.approvalId()))))
            throw new IllegalArgumentException("invocation binding mismatch");
        if (record.dispatchCount() == 1 && approvalStatus != null && !ApprovalStatus.APPROVED.name().equals(approvalStatus))
            throw new IllegalArgumentException("dispatch without confirmed approval");
        execution(record);
    }

    private void execution(ToolInvocationRecord record) {
        dispatchCount = record.dispatchCount(); dispatchAt = record.dispatchAt();
        outcome = record.outcome().name(); errorCode = record.errorCode(); executionMs = record.executionMillis();
    }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getOwnerId() { return ownerId; }
    public String getCallId() { return callId; }
    public String getArgumentsDigest() { return argumentsDigest; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchAt() { return dispatchAt; }
    public String getApprovalStatus() { return approvalStatus; }
    public String getActionSummary() { return actionSummary; }
    public String getArgumentPreviewJson() { return argumentPreviewJson; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getDecisionSource() { return decisionSource; }
}
