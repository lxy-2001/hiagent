package com.agentflow.core.approval;

import com.agentflow.core.tool.PreparedToolCall;
import com.agentflow.core.tool.ToolPolicyDecision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalRequest(UUID approvalId, PreparedToolCall preparedCall,
        ToolPolicyDecision policyDecision, Instant createdAt, Instant expiresAt) {
    public ApprovalRequest {
        Objects.requireNonNull(approvalId,"approvalId"); Objects.requireNonNull(preparedCall,"preparedCall");
        Objects.requireNonNull(policyDecision,"policyDecision"); Objects.requireNonNull(expiresAt,"expiresAt");
        if (!preparedCall.createdAt().equals(createdAt) || expiresAt.isBefore(createdAt)
                || policyDecision.action() != ToolPolicyDecision.Action.REQUIRE_APPROVAL)
            throw new IllegalArgumentException("invalid approval binding");
        expiresAt = expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
    @Override public String toString() { return "ApprovalRequest[approvalId=" + approvalId + "]"; }
}
