package com.agentflow.eval;

import java.util.List;

/** Scoring result contains no prompts, answers or raw evidence values. */
public record CaseReport(String caseId, Status status, List<AssertionResult> assertions) {
    public enum Status { PASS, FAIL, ERROR, INCOMPLETE, SKIPPED, NOT_RUN }
    public CaseReport { assertions = List.copyOf(assertions); }
}
