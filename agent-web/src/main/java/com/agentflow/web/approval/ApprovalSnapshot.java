package com.agentflow.web.approval;

import com.agentflow.core.approval.ApprovalStatus;
import com.agentflow.core.approval.ApprovalResolution.DecisionSource;
import com.agentflow.core.tool.RiskLevel;
import com.agentflow.core.tool.ToolInvocationRecord.Outcome;
import java.time.Instant;
import java.util.List;

public record ApprovalSnapshot(String approvalId, String taskId, String runId, String callId,
        String toolName, String serverId, String remoteToolName, RiskLevel risk, String actionSummary,
        List<ArgumentPreview> argumentPreview, String argumentsDigest, String definitionVersion,
        String policyVersion, ApprovalStatus status, Instant createdAt, Instant expiresAt, Instant decidedAt,
        DecisionSource decisionSource, int dispatchCount, Outcome outcome, Long waitMillis) {
    public ApprovalSnapshot { argumentPreview = List.copyOf(argumentPreview); }
    public record ArgumentPreview(String name, String value, boolean masked, boolean truncated) { }
}
