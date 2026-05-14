package com.agentflow.web.step;

import com.agentflow.core.AgentStepRecord;
import com.agentflow.core.step.StepRecorder;

public class NoopStepRecorder implements StepRecorder {

    @Override
    public void record(AgentStepRecord step) {
    }
}
