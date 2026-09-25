package com.agentflow.web.run;

public enum RunLifecycleStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    BUDGET_EXCEEDED;

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING && this != WAITING_APPROVAL;
    }
}
