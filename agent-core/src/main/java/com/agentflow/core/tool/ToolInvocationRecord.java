package com.agentflow.core.tool;

import com.agentflow.core.approval.ApprovalStatus;
import java.time.Instant;
import java.util.UUID;

/** Parameter-free facts captured by the runtime, distinct from the run outcome. */
public record ToolInvocationRecord(String schemaVersion, String runId, String callId, String toolName,
        String serverId, String remoteToolName, String definitionVersion, String policyVersion,
        String argumentsDigest, RiskLevel risk, ToolPolicyDecision.Effect effect,
        ToolPolicyDecision.Action policyAction, Instant createdAt, Instant dispatchAt,
        UUID approvalId, ApprovalStatus approvalStatus, Long approvalWaitMillis,
        int dispatchCount, Long executionMillis, Outcome outcome, String errorCode) {
    public ToolInvocationRecord {
        if (!"006-v1".equals(schemaVersion)) throw new IllegalArgumentException("unsupported schema version");
        text(runId, Integer.MAX_VALUE); text(callId,128); text(toolName,100);
        fingerprint(definitionVersion); fingerprint(policyVersion); fingerprint(argumentsDigest);
        java.util.Objects.requireNonNull(risk,"risk"); java.util.Objects.requireNonNull(effect,"effect");
        java.util.Objects.requireNonNull(policyAction,"policyAction"); java.util.Objects.requireNonNull(createdAt,"createdAt");
        java.util.Objects.requireNonNull(outcome,"outcome");
        if (serverId != null) text(serverId,32);
        if (remoteToolName != null) text(remoteToolName,64);
        if ((serverId == null) != (remoteToolName == null)) throw new IllegalArgumentException("remote identity must be paired");
        if (errorCode != null) text(errorCode,64);
        if (dispatchCount < 0 || dispatchCount > 1 || ((dispatchCount == 0) != (dispatchAt == null)))
            throw new IllegalArgumentException("invalid dispatch facts");
        if ((dispatchCount == 0) != (outcome == Outcome.NOT_DISPATCHED))
            throw new IllegalArgumentException("outcome does not match dispatch");
        if (policyAction == ToolPolicyDecision.Action.DENY && dispatchCount != 0)
            throw new IllegalArgumentException("denied invocation cannot dispatch");
        if (policyAction == ToolPolicyDecision.Action.ALLOW && (risk == RiskLevel.HIGH || effect == ToolPolicyDecision.Effect.WRITE))
            throw new IllegalArgumentException("unsafe ALLOW");
        if ((approvalId == null) != (approvalStatus == null) || (approvalId == null && approvalWaitMillis != null))
            throw new IllegalArgumentException("approval fields must be paired");
        if (approvalId != null && policyAction != ToolPolicyDecision.Action.REQUIRE_APPROVAL)
            throw new IllegalArgumentException("unexpected approval");
        if (dispatchCount == 1 && policyAction == ToolPolicyDecision.Action.REQUIRE_APPROVAL
                && approvalStatus != ApprovalStatus.APPROVED)
            throw new IllegalArgumentException("dispatch requires approval");
        if (approvalWaitMillis != null && approvalWaitMillis < 0 || executionMillis != null && executionMillis < 0)
            throw new IllegalArgumentException("negative duration");
    }
    private static void text(String value,int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid invocation field");
    }
    private static void fingerprint(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid fingerprint");
    }
    public enum Outcome { NOT_DISPATCHED, SUCCEEDED, FAILED, UNKNOWN }
}
