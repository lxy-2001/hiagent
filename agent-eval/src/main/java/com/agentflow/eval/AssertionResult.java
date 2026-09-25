package com.agentflow.eval;

public record AssertionResult(EvalCase.Rule id, boolean hard, Status status, String reasonCode,
                              Integer stepNo, String callId) {
    public enum Status { PASS, FAIL, INCOMPLETE, NOT_APPLICABLE }
}
