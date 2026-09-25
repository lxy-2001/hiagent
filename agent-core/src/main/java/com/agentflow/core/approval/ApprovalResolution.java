package com.agentflow.core.approval;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalResolution(UUID approvalId, ApprovalStatus status, Instant decidedAt,
        DecisionSource decisionSource, long waitMillis) {
    public enum DecisionSource { USER, TTL, RUN_TIMEOUT, CANCEL, POLICY, PROCESS }
    public ApprovalResolution {
        Objects.requireNonNull(approvalId,"approvalId"); Objects.requireNonNull(status,"status");
        Objects.requireNonNull(decisionSource,"decisionSource");
        if (status == ApprovalStatus.PENDING || waitMillis < 0)
            throw new IllegalArgumentException("approval decision must be final with nonnegative duration");
    }
}
