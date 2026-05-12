package com.agentflow.core.step;

import com.agentflow.core.AgentStepRecord;

public class NoopStepRecorder implements StepRecorder {

    @Override
    public void record(AgentStepRecord step) {
    }
}
