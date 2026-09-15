package com.agentflow.web.run;

public enum RunLifecycleStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    BUDGET_EXCEEDED;

    public boolean isTerminal() {
        return this != QUEUED && this != RUNNING;
    }
}
